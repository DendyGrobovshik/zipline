// happyPathNoInline fixture source. Each goBoomN carries a heavy
// computation (40 non-foldable operations) and is kept alive by an
// extra exported reference, so the Hermes optimizer inlines none of
// them and every frame survives in the stack trace.
fun sayHello() {
  goBoom3()
}

fun goBoom3() {
  goBoom2()
  val arr = mutableListOf<Int>()
  repeat(40) { arr += it }
}

fun goBoom2() {
  goBoom1()
  val arr = mutableListOf<Int>()
  repeat(40) { arr += it }
}

fun goBoom1() {
  val arr = mutableListOf<Int>()
  repeat(40) { arr += it }
  throw Error("boom!")
}

val keep3 = ::goBoom3
val keep2 = ::goBoom2
val keep1 = ::goBoom1
