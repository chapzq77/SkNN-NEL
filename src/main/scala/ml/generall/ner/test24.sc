


val l = List(
  (1,"a"),
  (2,"b"),
  (3,"c"),
  (2,"d"),
  (4,"e"),
  (1,"f")
)

l.sortBy(_._1)

case class TestClass(val x: Iterable[String]){

}



TestClass(List("4", "test"))