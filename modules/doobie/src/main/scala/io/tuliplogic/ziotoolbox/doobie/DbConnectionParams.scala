package io.tuliplogic.ziotoolbox.doobie

case class DbConnectionParams(
  url: String,
  user: String,
  password: String,
  maxConnections: Int,
)

case class DBError(message: String, cause: Option[Throwable] = None) extends Exception(message, cause.orNull)
