// sbt picks the plugin artifact line from the sbt version pinned in
// project/build.properties: `_2.12_1.0` on the 1.x line, `_sbt2_3` on 2.x.
// This build is on sbt 2, so `sbt-scalafmt_sbt2_3` is what gets resolved.
// The scalafmt version itself is pinned in .scalafmt.conf, not here.
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")
