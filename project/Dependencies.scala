import sbt._

object Dependencies {
  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.0.3"
  
  val akka = {
    val AkkaVersion = "2.5.6"
    Seq("com.typesafe.akka"          %% "akka-typed"       % AkkaVersion)
  }
  
  val akkaTest = {
    Seq(
      "org.iq80.leveldb"            % "leveldb"          % "0.7",
      "org.fusesource.leveldbjni"   % "leveldbjni-all"   % "1.8"
    )
  }
  
}
