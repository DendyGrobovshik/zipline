#include <jsi/jsi.h>
#include <stdint.h>
#include <stdbool.h>

#define META_EMPTY 0x80
#define META_DELETED 0xFE
#define META_SENTINEL 0xFF

namespace jsi = facebook::jsi;

// Read a single int32_t element from an array-like value.
// This is the JSI-compatible way to access array elements.
static bool read_int_element(jsi::Runtime& rt, const jsi::Value& arr, int32_t index, int32_t* result) {
  if (!arr.isObject()) {
    return false;
  }
  jsi::Object obj = arr.asObject(rt);
  jsi::Value val = obj.getProperty(rt, index);
  if (!val.isNumber()) {
    return false;
  }
  *result = static_cast<int32_t>(val.asNumber());
  return true;
}

// Match the 8-byte group against hash2 (0..127). Each set bit 8k+7 indicates
// that byte k matches hash2. Implementation matches the Kotlin/JS version:
//   m = (x - 0x01010101) & ~x & 0x80808080
static inline uint64_t match_hash2(uint64_t g, int32_t hash2) {
  uint64_t x = g ^ (uint64_t)(uint8_t)hash2 * 0x0101010101010101ULL;
  return (x - 0x0101010101010101ULL) & ~x & 0x8080808080808080ULL;
}

// Detect bytes equal to META_EMPTY (0x80)
static inline uint64_t mask_empty(uint64_t g) {
    // XOR with 0x80 to turn empty bytes into zero, then zero-detect
    uint64_t x = g ^ 0x8080808080808080ULL;
    return (x - 0x0101010101010101ULL) & ~x & 0x8080808080808080ULL;
}

// Combined empty or deleted mask
static inline uint64_t mask_empty_or_deleted(uint64_t g) {
    uint64_t not_g = ~g;
    uint64_t shifted = not_g << 7;
    uint64_t temp = g & shifted;
    return temp & 0x8080808080808080ULL;
}

// Check if any empty byte exists
static inline int32_t any_empty(uint64_t g) {
    return mask_empty(g) != 0;
}

// Check if any empty or deleted byte exists
static inline int32_t any_empty_or_deleted(uint64_t g) {
    return mask_empty_or_deleted(g) != 0;
}

// ============================================================================
// IntSet intrinsics. Elements are Int32 values stored in IntArray. We read
// and compare them directly as int32_t.
// ============================================================================

// Read metadata from an array-like value (e.g., Int32Array or Array).
// For JSI, we access elements via getProperty instead of raw pointers.
// This is slower but JSI-compatible.
static bool load_metadata_group(jsi::Runtime& rt, const jsi::Value& meta, int32_t offset, uint64_t* group) {
  // For simplicity, we assume meta is an Int32Array or similar with elements
  // stored as int32_t values at offsets 0, 2, 4, ... (every 4 bytes).
  // For JSI, we can't access raw pointers, so we read 8 elements at a time
  // and combine them into the 64-bit group.

  int32_t byteOffset = offset;
  int32_t i = byteOffset >> 3;  // word index
  int32_t b = (byteOffset & 0x7) << 3;  // bit offset

  // Read 8 int32_t values starting at word index i
  // Each word is 8 bytes = 2 int32_t values
  uint64_t lo = 0;
  uint64_t hi = 0;

  for (int32_t j = 0; j < 2; j++) {
    int32_t idx = (i + j) * 2;
    jsi::Value val = meta.asObject(rt).getProperty(rt, idx);
    if (!val.isNumber()) return false;
    int32_t lo_part = static_cast<int32_t>(val.asNumber());
    val = meta.asObject(rt).getProperty(rt, idx + 1);
    if (!val.isNumber()) return false;
    int32_t hi_part = static_cast<int32_t>(val.asNumber());

    uint64_t word = ((uint64_t)(uint32_t)hi_part << 32) | (uint32_t)lo_part;
    if (j == 0) {
      lo = word;
    } else {
      hi = word;
    }
  }

  if (b == 0) {
    *group = lo;
  } else {
    *group = (lo >> b) | (hi << (64 - b));
  }
  return true;
}

// Write metadata byte to an array-like value.
// JSI has no raw ArrayBuffer access, so read-modify-write the containing
// int32 element via indexed properties (little-endian byte order, matching
// load_metadata_group).
static bool write_metadata_byte(jsi::Runtime& rt, const jsi::Value& meta, int32_t offset, uint8_t byte) {
  if (!meta.isObject()) return false;
  jsi::Object obj = meta.asObject(rt);
  int32_t idx = offset >> 2;
  uint32_t shift = static_cast<uint32_t>(offset & 3) << 3;
  jsi::Value val = obj.getProperty(rt, idx);
  if (!val.isNumber()) return false;
  uint32_t word = static_cast<uint32_t>(static_cast<int32_t>(val.asNumber()));
  word = (word & ~(0xFFu << shift)) | (static_cast<uint32_t>(byte) << shift);
  obj.setProperty(rt, idx, jsi::Value(static_cast<int32_t>(word)));
  return true;
}

// Write a single int32 element into an array-like value (out params).
static bool write_int_element(jsi::Runtime& rt, const jsi::Value& arr, int32_t index, int32_t value) {
  if (!arr.isObject()) return false;
  arr.asObject(rt).setProperty(rt, index, jsi::Value(value));
  return true;
}

// Probe for the first Empty or Deleted slot from hash1, mirroring the
// QuickJS find_first_available_slot. Returns -1 only on metadata read errors
// (callers convert that to a JSError).
static int32_t find_first_available_slot(jsi::Runtime& rt, const jsi::Value& meta,
                                         int32_t capacity, int32_t hash1, bool* ok) {
  int32_t mask = capacity;
  int32_t probeOffset = hash1 & mask;
  int32_t probeIndex = 0;
  while (1) {
    uint64_t g;
    if (!load_metadata_group(rt, meta, probeOffset, &g)) {
      *ok = false;
      return -1;
    }
    uint64_t m = mask_empty_or_deleted(g);
    if (m != 0) {
      int32_t bitIdx = __builtin_ctzll(m);
      int32_t byteInGroup = bitIdx >> 3;
      *ok = true;
      return (probeOffset + byteInGroup) & mask;
    }
    probeIndex += 8;
    probeOffset = (probeOffset + probeIndex) & mask;
  }
}

static jsi::Value c_intset_find(jsi::Runtime& rt, const jsi::Value& this_val,
                                   const jsi::Value* args, size_t argc) {
  if (argc < 6) {
    throw jsi::JSError(rt, "_intsetFind expects 6 args");
  }

  // args[0]=meta, args[1]=elements, args[2]=capacity, args[3]=element, args[4]=hash, args[5]=hash2
  int32_t capacity = static_cast<int32_t>(args[2].asNumber());
  int32_t element = static_cast<int32_t>(args[3].asNumber());
  int32_t hash = static_cast<int32_t>(args[4].asNumber());
  int32_t hash2 = static_cast<int32_t>(args[5].asNumber());

  int32_t mask = capacity;
  int32_t probeOffset = ((uint32_t)hash >> 7) & mask;
  int32_t probeIndex = 0;

  while (1) {
    uint64_t g;
    if (!load_metadata_group(rt, args[0], probeOffset, &g)) {
      throw jsi::JSError(rt, "Failed to load metadata");
    }
    uint64_t m = match_hash2(g, hash2);
    while (m != 0) {
      int32_t bitIdx = __builtin_ctzll(m);
      int32_t byteInGroup = bitIdx >> 3;
      int32_t index = (probeOffset + byteInGroup) & mask;
      jsi::Value slotVal = args[1].asObject(rt).getProperty(rt, index);
      if (!slotVal.isNumber()) {
        throw jsi::JSError(rt, "Element is not a number");
      }
      int32_t slotInt = static_cast<int32_t>(slotVal.asNumber());
      if (slotInt == element) {
        return jsi::Value(index);
      }
      m &= m - 1;
    }
    if (any_empty_or_deleted(g)) {
      break;
    }
    probeIndex += 8;
    probeOffset = (probeOffset + probeIndex) & mask;
  }
  return jsi::Value(-1);
}

static jsi::Value c_intset_find_slot(jsi::Runtime& rt, const jsi::Value& this_val,
                                        const jsi::Value* args, size_t argc) {
  if (argc < 7) {
    throw jsi::JSError(rt, "_intsetFindSlot expects 7 args");
  }

  int32_t capacity = static_cast<int32_t>(args[2].asNumber());
  int32_t element = static_cast<int32_t>(args[3].asNumber());
  int32_t hash = static_cast<int32_t>(args[4].asNumber());
  int32_t hash2 = static_cast<int32_t>(args[5].asNumber());

  int32_t mask = capacity;
  int32_t probeOffset = ((uint32_t)hash >> 7) & mask;
  int32_t probeIndex = 0;

  while (1) {
    uint64_t g;
    if (!load_metadata_group(rt, args[0], probeOffset, &g)) {
      throw jsi::JSError(rt, "Failed to load metadata");
    }
    uint64_t m = match_hash2(g, hash2);
    while (m != 0) {
      int32_t bitIdx = __builtin_ctzll(m);
      int32_t byteInGroup = bitIdx >> 3;
      int32_t index = (probeOffset + byteInGroup) & mask;
      int32_t slotInt;
      if (!read_int_element(rt, args[1], index, &slotInt)) {
        throw jsi::JSError(rt, "Element is not a number");
      }
      if (slotInt == element) {
        return jsi::Value(index);
      }
      m &= m - 1;
    }
    if (any_empty(g)) {
      break;
    }
    probeIndex += 8;
    probeOffset = (probeOffset + probeIndex) & mask;
  }

  // Not found: report the first available slot via the out param.
  bool ok = false;
  int32_t slot = find_first_available_slot(rt, args[0], capacity, ((uint32_t)hash >> 7) & mask, &ok);
  if (!ok || !write_int_element(rt, args[6], 0, slot)) {
    throw jsi::JSError(rt, "Failed to write emptySlot out param");
  }
  return jsi::Value(-1);
}

static jsi::Value c_intset_remove(jsi::Runtime& rt, const jsi::Value& this_val,
                                     const jsi::Value* args, size_t argc) {
  if (argc < 6) {
    throw jsi::JSError(rt, "_intsetRemove expects 6 args");
  }

  int32_t capacity = static_cast<int32_t>(args[2].asNumber());
  int32_t element = static_cast<int32_t>(args[3].asNumber());
  int32_t hash = static_cast<int32_t>(args[4].asNumber());
  int32_t hash2 = static_cast<int32_t>(args[5].asNumber());

  int32_t mask = capacity;
  int32_t probeOffset = ((uint32_t)hash >> 7) & mask;
  int32_t probeIndex = 0;

  while (1) {
    uint64_t g;
    if (!load_metadata_group(rt, args[0], probeOffset, &g)) {
      throw jsi::JSError(rt, "Failed to load metadata");
    }
    uint64_t m = match_hash2(g, hash2);
    while (m != 0) {
      int32_t bitIdx = __builtin_ctzll(m);
      int32_t byteInGroup = bitIdx >> 3;
      int32_t index = (probeOffset + byteInGroup) & mask;
      jsi::Value slotVal = args[1].asObject(rt).getProperty(rt, index);
      if (!slotVal.isNumber()) {
        throw jsi::JSError(rt, "Element is not a number");
      }
      int32_t slotInt = static_cast<int32_t>(slotVal.asNumber());
      if (slotInt == element) {
        // Tombstone the slot in metadata, then clear the element.
        if (!write_metadata_byte(rt, args[0], index, META_DELETED)) {
          throw jsi::JSError(rt, "Failed to write metadata");
        }
        args[1].asObject(rt).setProperty(rt, index, jsi::Value::null());
        return jsi::Value(index);
      }
      m &= m - 1;
    }
    if (any_empty(g)) break;
    probeIndex += 8;
    probeOffset = (probeOffset + probeIndex) & mask;
  }
  return jsi::Value(-1);
}

// ============================================================================
// IntObjectMap intrinsics. Keys are Int32. For keys we compare directly as int32_t.
// ============================================================================

static jsi::Value c_int_object_map_find(jsi::Runtime& rt, const jsi::Value& this_val,
                                            const jsi::Value* args, size_t argc) {
  if (argc < 6) {
    throw jsi::JSError(rt, "_intObjectMapFind expects 6 args");
  }

  int32_t capacity = static_cast<int32_t>(args[2].asNumber());
  int32_t key = static_cast<int32_t>(args[3].asNumber());
  int32_t hash = static_cast<int32_t>(args[4].asNumber());
  int32_t hash2 = static_cast<int32_t>(args[5].asNumber());

  int32_t mask = capacity;
  int32_t probeOffset = ((uint32_t)hash >> 7) & mask;
  int32_t probeIndex = 0;

  while (1) {
    uint64_t g;
    if (!load_metadata_group(rt, args[0], probeOffset, &g)) {
      throw jsi::JSError(rt, "Failed to load metadata");
    }
    uint64_t m = match_hash2(g, hash2);
    while (m != 0) {
      int32_t bitIdx = __builtin_ctzll(m);
      int32_t byteInGroup = bitIdx >> 3;
      int32_t index = (probeOffset + byteInGroup) & mask;
      jsi::Value slotVal = args[1].asObject(rt).getProperty(rt, index);
      if (!slotVal.isNumber()) {
        throw jsi::JSError(rt, "Key is not a number");
      }
      int32_t slotKey = static_cast<int32_t>(slotVal.asNumber());
      if (slotKey == key) {
        return jsi::Value(index);
      }
      m &= m - 1;
    }
    if (any_empty_or_deleted(g)) {
      break;
    }
    probeIndex += 8;
    probeOffset = (probeOffset + probeIndex) & mask;
  }
  return jsi::Value(-1);
}

static jsi::Value c_int_object_map_find_slot(jsi::Runtime& rt, const jsi::Value& this_val,
                                                  const jsi::Value* args, size_t argc) {
  if (argc < 7) {
    throw jsi::JSError(rt, "_intObjectMapFindSlot expects 7 args");
  }

  int32_t capacity = static_cast<int32_t>(args[2].asNumber());
  int32_t key = static_cast<int32_t>(args[3].asNumber());
  int32_t hash = static_cast<int32_t>(args[4].asNumber());
  int32_t hash2 = static_cast<int32_t>(args[5].asNumber());

  int32_t mask = capacity;
  int32_t probeOffset = ((uint32_t)hash >> 7) & mask;
  int32_t probeIndex = 0;

  while (1) {
    uint64_t g;
    if (!load_metadata_group(rt, args[0], probeOffset, &g)) {
      throw jsi::JSError(rt, "Failed to load metadata");
    }
    uint64_t m = match_hash2(g, hash2);
    while (m != 0) {
      int32_t bitIdx = __builtin_ctzll(m);
      int32_t byteInGroup = bitIdx >> 3;
      int32_t index = (probeOffset + byteInGroup) & mask;
      jsi::Value slotVal = args[1].asObject(rt).getProperty(rt, index);
      if (!slotVal.isNumber()) {
        throw jsi::JSError(rt, "Key is not a number");
      }
      int32_t slotKey = static_cast<int32_t>(slotVal.asNumber());
      if (slotKey == key) {
        return jsi::Value(index);
      }
      m &= m - 1;
    }
    if (any_empty(g)) {
      break;
    }
    probeIndex += 8;
    probeOffset = (probeOffset + probeIndex) & mask;
  }
  // Not found: report the first available slot via the out param.
  bool ok = false;
  int32_t slot = find_first_available_slot(rt, args[0], capacity, ((uint32_t)hash >> 7) & mask, &ok);
  if (!ok || !write_int_element(rt, args[6], 0, slot)) {
    throw jsi::JSError(rt, "Failed to write emptySlot out param");
  }
  return jsi::Value(-1);
}

static jsi::Value c_int_object_map_put(jsi::Runtime& rt, const jsi::Value& this_val,
                                           const jsi::Value* args, size_t argc) {
  if (argc < 8) {
    throw jsi::JSError(rt, "_intObjectMapPut expects 8 args");
  }

  int32_t capacity = static_cast<int32_t>(args[2].asNumber());
  int32_t key = static_cast<int32_t>(args[3].asNumber());
  int32_t hash = static_cast<int32_t>(args[4].asNumber());
  int32_t hash2 = static_cast<int32_t>(args[5].asNumber());

  int32_t mask = capacity;
  int32_t probeOffset = ((uint32_t)hash >> 7) & mask;
  int32_t probeIndex = 0;

  while (1) {
    uint64_t g;
    if (!load_metadata_group(rt, args[0], probeOffset, &g)) {
      throw jsi::JSError(rt, "Failed to load metadata");
    }
    uint64_t m = match_hash2(g, hash2);
    while (m != 0) {
      int32_t bitIdx = __builtin_ctzll(m);
      int32_t byteInGroup = bitIdx >> 3;
      int32_t index = (probeOffset + byteInGroup) & mask;
      jsi::Value slotVal = args[1].asObject(rt).getProperty(rt, index);
      if (!slotVal.isNumber()) {
        throw jsi::JSError(rt, "Key is not a number");
      }
      int32_t slotKey = static_cast<int32_t>(slotVal.asNumber());
      if (slotKey == key) {
        // Found existing key, no creation needed
        if (!write_int_element(rt, args[6], 0, 0) ||
            !write_int_element(rt, args[7], 0, 0)) {
          throw jsi::JSError(rt, "Failed to write out params");
        }
        return jsi::Value(index);
      }
      m &= m - 1;
    }
    if (any_empty(g)) {
      // Found empty slot, insert here
      int32_t bitIdx = __builtin_ctzll(mask_empty(g));
      int32_t byteInGroup = bitIdx >> 3;
      int32_t index = (probeOffset + byteInGroup) & mask;
      if (!write_metadata_byte(rt, args[0], index, static_cast<uint8_t>(hash2))) {
        throw jsi::JSError(rt, "Failed to write metadata");
      }
      args[1].asObject(rt).setProperty(rt, index, jsi::Value(key));
      if (!write_int_element(rt, args[6], 0, 1) ||
          !write_int_element(rt, args[7], 0, 1)) {
        throw jsi::JSError(rt, "Failed to write out params");
      }
      return jsi::Value(index);
    }
    probeIndex += 8;
    probeOffset = (probeOffset + probeIndex) & mask;
  }
}

static jsi::Value c_int_object_map_remove(jsi::Runtime& rt, const jsi::Value& this_val,
                                              const jsi::Value* args, size_t argc) {
  if (argc < 6) {
    throw jsi::JSError(rt, "_intObjectMapRemove expects 6 args");
  }

  int32_t capacity = static_cast<int32_t>(args[2].asNumber());
  int32_t key = static_cast<int32_t>(args[3].asNumber());
  int32_t hash = static_cast<int32_t>(args[4].asNumber());
  int32_t hash2 = static_cast<int32_t>(args[5].asNumber());

  int32_t mask = capacity;
  int32_t probeOffset = ((uint32_t)hash >> 7) & mask;
  int32_t probeIndex = 0;

  while (1) {
    uint64_t g;
    if (!load_metadata_group(rt, args[0], probeOffset, &g)) {
      throw jsi::JSError(rt, "Failed to load metadata");
    }
    uint64_t m = match_hash2(g, hash2);
    while (m != 0) {
      int32_t bitIdx = __builtin_ctzll(m);
      int32_t byteInGroup = bitIdx >> 3;
      int32_t index = (probeOffset + byteInGroup) & mask;
      jsi::Value slotVal = args[1].asObject(rt).getProperty(rt, index);
      if (!slotVal.isNumber()) {
        throw jsi::JSError(rt, "Key is not a number");
      }
      int32_t slotKey = static_cast<int32_t>(slotVal.asNumber());
      if (slotKey == key) {
        if (!write_metadata_byte(rt, args[0], index, META_DELETED)) {
          throw jsi::JSError(rt, "Failed to write metadata");
        }
        args[1].asObject(rt).setProperty(rt, index, jsi::Value::null());
        return jsi::Value(index);
      }
      m &= m - 1;
    }
    if (any_empty(g)) break;
    probeIndex += 8;
    probeOffset = (probeOffset + probeIndex) & mask;
  }
  return jsi::Value(-1);
}

static jsi::Value c_int_object_map_find_available_slot(jsi::Runtime& rt, const jsi::Value& this_val,
                                                             const jsi::Value* args, size_t argc) {
  if (argc < 3) {
    throw jsi::JSError(rt, "_intObjectMapFindAvailableSlot expects 3 args");
  }

  int32_t capacity = static_cast<int32_t>(args[1].asNumber());
  int32_t hash1 = static_cast<int32_t>(args[2].asNumber());

  int32_t mask = capacity;
  int32_t probeOffset = hash1 & mask;
  int32_t probeIndex = 0;
  while (1) {
    uint64_t g;
    if (!load_metadata_group(rt, args[0], probeOffset, &g)) {
      throw jsi::JSError(rt, "Failed to load metadata");
    }
    uint64_t m = mask_empty_or_deleted(g);
    if (m != 0) {
      int32_t bitIdx = __builtin_ctzll(m);
      int32_t byteInGroup = bitIdx >> 3;
      int32_t result = (probeOffset + byteInGroup) & mask;
      return jsi::Value(result);
    }
    probeIndex += 8;
    probeOffset = (probeOffset + probeIndex) & mask;
  }
}

// Array copy: copies elements from source to destination
// args[0]=source, args[1]=destination, args[2]=destinationOffset, args[3]=startIndex, args[4]=endIndex
static jsi::Value c_array_copy(jsi::Runtime& rt, const jsi::Value& this_val,
                                  const jsi::Value* args, size_t argc) {
  if (argc < 5) {
    throw jsi::JSError(rt, "_arrayCopy expects 5 args");
  }

  const jsi::Value& source = args[0];
  const jsi::Value& destination = args[1];
  int32_t destOffset = static_cast<int32_t>(args[2].asNumber());
  int32_t startIndex = static_cast<int32_t>(args[3].asNumber());
  int32_t endIndex = static_cast<int32_t>(args[4].asNumber());
  int32_t rangeSize = endIndex - startIndex;

  bool isSourceArray = source.isObject() && source.asObject(rt).isArray(rt);
  bool isDestArray = destination.isObject() && destination.asObject(rt).isArray(rt);

  jsi::Value srcBuffer = source.asObject(rt).getProperty(rt, "buffer");
  jsi::Value dstBuffer = destination.asObject(rt).getProperty(rt, "buffer");
  bool srcBufferIsObject = srcBuffer.isObject() && !srcBuffer.isNull();
  bool dstBufferIsObject = dstBuffer.isObject() && !dstBuffer.isNull();

  bool isSourceTypedArray = !isSourceArray && srcBufferIsObject;
  bool isDestTypedArray = !isDestArray && dstBufferIsObject;

  if (isSourceTypedArray && isDestTypedArray) {
    jsi::Value subarrayFn = source.asObject(rt).getProperty(rt, "subarray");
    jsi::Value setFn = destination.asObject(rt).getProperty(rt, "set");

    if (subarrayFn.isObject() && setFn.isObject()) {
      const jsi::Value subarrayArgs[] = { jsi::Value(startIndex), jsi::Value(endIndex) };
      jsi::Value subarray = subarrayFn.asObject(rt).asFunction(rt).callWithThis(
          rt, source.asObject(rt), subarrayArgs, static_cast<size_t>(2));
      if (!subarray.isUndefined() && !subarray.isNull()) {
        jsi::Value destOffsetVal = jsi::Value(destOffset);
        const jsi::Value setArgs[] = { std::move(subarray), std::move(destOffsetVal) };
        jsi::Value result = setFn.asObject(rt).asFunction(rt).callWithThis(
            rt, destination.asObject(rt), setArgs, static_cast<size_t>(2));
        return jsi::Value::undefined();
      }
    }
  }

  bool sameArray = jsi::Value::strictEquals(rt, source, destination);
  if (sameArray && destOffset > startIndex) {
    for (int32_t i = rangeSize - 1; i >= 0; i--) {
      jsi::Value val = source.asObject(rt).getProperty(rt, jsi::Value(startIndex + i));
      destination.asObject(rt).setProperty(rt, jsi::Value(destOffset + i), val);
    }
  } else {
    for (int32_t i = 0; i < rangeSize; i++) {
      jsi::Value val = source.asObject(rt).getProperty(rt, jsi::Value(startIndex + i));
      destination.asObject(rt).setProperty(rt, jsi::Value(destOffset + i), val);
    }
  }
  return jsi::Value::undefined();
}

// Computes Kotlin's String.hashCode(): hash = s[0]*31^(n-1) + s[1]*31^(n-2) + ... + s[n-1]
// which is implemented as iterative: hash = hash * 31 + charAt(i)
// JS charCodeAt returns UTF-16 code units (16-bit values)
static jsi::Value c_get_string_hash_code(jsi::Runtime& rt, const jsi::Value& this_val,
                                             const jsi::Value* args, size_t argc) {
  if (argc < 1) {
    throw jsi::JSError(rt, "_getStringHashCode expects 1 arg");
  }

  const jsi::Value& str = args[0];

  if (!str.isString()) {
    return jsi::Value(0);
  }

  std::string s = str.asString(rt).utf8(rt);
  size_t byteLen = s.length();

  if (byteLen == 0) {
    return jsi::Value(0);
  }

  // Decode UTF-8 to UTF-16 and compute hash
  // This matches JS charCodeAt behavior: each char is a 16-bit UTF-16 code unit
  int32_t hash = 0;
  size_t i = 0;
  const char* cstr = s.c_str();

  while (i < byteLen) {
    uint32_t code;
    // Decode UTF-8 character
    if ((cstr[i] & 0x80) == 0) {
      // 1-byte sequence (ASCII)
      code = (uint8_t)cstr[i];
      i += 1;
    } else if ((cstr[i] & 0xE0) == 0xC0) {
      // 2-byte sequence - bounds check
      if (i + 1 >= byteLen) break;
      code = ((uint8_t)cstr[i] & 0x1F) << 6;
      code |= ((uint8_t)cstr[i + 1] & 0x3F);
      i += 2;
    } else if ((cstr[i] & 0xF0) == 0xE0) {
      // 3-byte sequence - bounds check
      if (i + 2 >= byteLen) break;
      code = ((uint8_t)cstr[i] & 0x0F) << 12;
      code |= ((uint8_t)cstr[i + 1] & 0x3F) << 6;
      code |= ((uint8_t)cstr[i + 2] & 0x3F);
      i += 3;
    } else {
      // 4-byte sequence - bounds check
      if (i + 3 >= byteLen) break;
      // 4-byte sequence represents a Unicode code point outside BMP (U+10000 and above)
      // Decode to get the full code point
      uint32_t fullCodePoint = ((uint8_t)cstr[i] & 0x07) << 18;
      fullCodePoint |= ((uint8_t)cstr[i + 1] & 0x3F) << 12;
      fullCodePoint |= ((uint8_t)cstr[i + 2] & 0x3F) << 6;
      fullCodePoint |= ((uint8_t)cstr[i + 3] & 0x3F);
      i += 4;

      // Convert to UTF-16 surrogate pairs (what JS charCodeAt returns)
      // High surrogate: 0xD800 + top 10 bits of (codePoint - 0x10000)
      // Low surrogate:  0xDC00 + bottom 10 bits of (codePoint - 0x10000)
      uint32_t adjusted = fullCodePoint - 0x10000;
      uint32_t highSurrogate = 0xD800 + (adjusted >> 10);
      uint32_t lowSurrogate = 0xDC00 + (adjusted & 0x3FF);

      // First char
      hash = (int32_t)((int32_t)hash * 31 + (int32_t)highSurrogate);
      // Second char
      hash = (int32_t)((int32_t)hash * 31 + (int32_t)lowSurrogate);
      continue;
    }
    // hash * 31 + char, with 32-bit signed integer overflow (same as JS.imul)
    hash = (int32_t)((int32_t)hash * 31 + (int32_t)code);
  }

  return jsi::Value(hash);
}

extern "C" __attribute__((visibility("default"))) void js_register_intrinsics(void* runtime) {
  jsi::Runtime& rt = *static_cast<jsi::Runtime*>(runtime);
  jsi::Object globalThis = rt.global();

  // IntSet intrinsics
  globalThis.setProperty(rt, "_intsetFind",
      jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forUtf8(rt, "_intsetFind"), 6, c_intset_find));
  globalThis.setProperty(rt, "_intsetFindSlot",
      jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forUtf8(rt, "_intsetFindSlot"), 7, c_intset_find_slot));
  globalThis.setProperty(rt, "_intsetRemove",
      jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forUtf8(rt, "_intsetRemove"), 6, c_intset_remove));

  // IntObjectMap intrinsics
  globalThis.setProperty(rt, "_intObjectMapFind",
      jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forUtf8(rt, "_intObjectMapFind"), 6, c_int_object_map_find));
  globalThis.setProperty(rt, "_intObjectMapFindSlot",
      jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forUtf8(rt, "_intObjectMapFindSlot"), 7, c_int_object_map_find_slot));
  globalThis.setProperty(rt, "_intObjectMapPut",
      jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forUtf8(rt, "_intObjectMapPut"), 8, c_int_object_map_put));
  globalThis.setProperty(rt, "_intObjectMapRemove",
      jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forUtf8(rt, "_intObjectMapRemove"), 6, c_int_object_map_remove));
  globalThis.setProperty(rt, "_intObjectMapFindAvailableSlot",
      jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forUtf8(rt, "_intObjectMapFindAvailableSlot"), 3, c_int_object_map_find_available_slot));

  // String hashCode helper
  globalThis.setProperty(rt, "_getStringHashCode",
      jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forUtf8(rt, "_getStringHashCode"), 1, c_get_string_hash_code));

  // Array copy helper
  globalThis.setProperty(rt, "_arrayCopy",
      jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forUtf8(rt, "_arrayCopy"), 5, c_array_copy));
}
