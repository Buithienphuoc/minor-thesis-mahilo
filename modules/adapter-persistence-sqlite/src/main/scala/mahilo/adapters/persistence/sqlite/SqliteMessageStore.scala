package mahilo.adapters.persistence.sqlite

import java.sql.{PreparedStatement, ResultSet, Types}
import java.time.Instant
import cats.effect.IO
import mahilo.application.port.out.{MessageQuery, MessageStore}
import mahilo.domain.{AgentName, MessageId}
import mahilo.domain.messaging.*

/** ADAPTER cho port `MessageStore` bằng SQLite. Python: `SQLiteMessageStore`.
  * Lược đồ giữ gần như nguyên bản Python để dễ đối chiếu.
  */
final class SqliteMessageStore private (db: Sqlite) extends MessageStore:

  private val Columns =
    "message_id, sender, recipient, message_type, payload, timestamp, correlation_id, reply_to, signature, state, retry_count"

  def save(e: Envelope, state: DeliveryState): IO[Unit] =
    db.update(s"INSERT INTO messages ($Columns) VALUES (?,?,?,?,?,?,?,?,?,?,?)") { st =>
      st.setString(1, e.id.value)
      st.setString(2, e.sender.value)
      st.setString(3, e.recipient.value)
      st.setString(4, e.kind.wire)
      st.setString(5, e.payload)
      st.setLong(6, e.timestamp.toEpochMilli)
      setOpt(st, 7, e.correlationId)
      setOpt(st, 8, e.replyTo.map(_.value))
      setOpt(st, 9, e.signature)
      st.setString(10, state.label)
      st.setInt(11, state.retryCount)
    }.void

  def get(id: MessageId): IO[Option[StoredMessage]] =
    db.query(s"SELECT $Columns FROM messages WHERE message_id = ?")(_.setString(1, id.value))(row).map(_.headOption)

  def updateState(id: MessageId, state: DeliveryState): IO[Unit] =
    db.update("UPDATE messages SET state = ?, retry_count = ?, last_retry = ? WHERE message_id = ?") { st =>
      st.setString(1, state.label)
      st.setInt(2, state.retryCount)
      st.setLong(3, System.currentTimeMillis())
      st.setString(4, id.value)
    }.void

  def pending(recipient: AgentName): IO[List[StoredMessage]] =
    db.query(s"SELECT $Columns FROM messages WHERE recipient = ? AND state = 'pending' ORDER BY timestamp ASC")(
      _.setString(1, recipient.value)
    )(row)

  def recipientsWithPending: IO[Set[AgentName]] =
    db.query("SELECT DISTINCT recipient FROM messages WHERE state = 'pending'")()(rs => AgentName(rs.getString(1))).map(_.toSet)

  /** Lấy `limit` tin MỚI nhất (DESC) rồi đảo lại để trả CŨ → MỚI như port yêu cầu. */
  def conversation(a: AgentName, b: AgentName, limit: Int): IO[List[Envelope]] =
    db.query(
      s"""SELECT $Columns FROM messages
         |WHERE (sender = ? AND recipient = ?) OR (sender = ? AND recipient = ?)
         |ORDER BY timestamp DESC LIMIT ?""".stripMargin
    ) { st =>
      st.setString(1, a.value); st.setString(2, b.value); st.setString(3, b.value); st.setString(4, a.value); st.setInt(5, limit)
    }(row).map(_.map(_.envelope).reverse)

  def query(q: MessageQuery): IO[List[StoredMessage]] =
    val filters = List(
      q.sender.map(s => ("sender = ?", (st: PreparedStatement, i: Int) => st.setString(i, s.value))),
      q.recipient.map(r => ("recipient = ?", (st: PreparedStatement, i: Int) => st.setString(i, r.value))),
      q.from.map(t => ("timestamp >= ?", (st: PreparedStatement, i: Int) => st.setLong(i, t.toEpochMilli))),
      q.to.map(t => ("timestamp <= ?", (st: PreparedStatement, i: Int) => st.setLong(i, t.toEpochMilli)))
    ).flatten
    val where = if filters.isEmpty then "" else filters.map(_._1).mkString("WHERE ", " AND ", "")
    db.query(s"SELECT $Columns FROM messages $where ORDER BY timestamp DESC LIMIT ?") { st =>
      filters.zipWithIndex.foreach { case ((_, bind), i) => bind(st, i + 1) }
      st.setInt(filters.size + 1, q.limit)
    }(row)

  // ---------- ánh xạ hàng ↔ domain ----------

  private def row(rs: ResultSet): StoredMessage =
    val env = Envelope(
      id = MessageId(rs.getString("message_id")),
      sender = AgentName(rs.getString("sender")),
      recipient = AgentName(rs.getString("recipient")),
      kind = MessageKind.fromWire(rs.getString("message_type")).getOrElse(MessageKind.Direct),
      payload = rs.getString("payload"),
      timestamp = Instant.ofEpochMilli(rs.getLong("timestamp")),
      correlationId = Option(rs.getString("correlation_id")),
      replyTo = Option(rs.getString("reply_to")).map(MessageId(_)),
      signature = Option(rs.getString("signature"))
    )
    val retries = rs.getInt("retry_count")
    val state = rs.getString("state") match
      case "processed" => DeliveryState.Processed
      case "failed"    => DeliveryState.Failed(retries)
      case _           => DeliveryState.Pending(retries)
    StoredMessage(env, state)

  private def setOpt(st: PreparedStatement, i: Int, v: Option[String]): Unit =
    v.fold(st.setNull(i, Types.VARCHAR))(st.setString(i, _))

object SqliteMessageStore:
  def make(path: String): IO[SqliteMessageStore] =
    val db = Sqlite(path)
    db.update(
      """CREATE TABLE IF NOT EXISTS messages (
        |  message_id     TEXT PRIMARY KEY,
        |  sender         TEXT NOT NULL,
        |  recipient      TEXT NOT NULL,
        |  message_type   TEXT NOT NULL,
        |  payload        TEXT NOT NULL,
        |  timestamp      INTEGER NOT NULL,  -- epoch millis (Python: REAL epoch giây)
        |  correlation_id TEXT,
        |  reply_to       TEXT,
        |  signature      TEXT,
        |  state          TEXT NOT NULL,
        |  retry_count    INTEGER DEFAULT 0,
        |  last_retry     INTEGER,
        |  created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        |)""".stripMargin
    )() *>
      db.update("CREATE INDEX IF NOT EXISTS idx_messages_recipient_state ON messages(recipient, state)")() *>
      IO.pure(SqliteMessageStore(db))
