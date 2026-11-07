package mahilo.adapters.persistence.sqlite

import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet}
import cats.effect.{IO, Resource}
import scala.util.Using

/** Tiện ích JDBC tối giản. Cố ý không dùng Doobie/Quill để người đọc thấy rõ SQL.
  * Mỗi thao tác mở một kết nối riêng (giống Python), chạy trên thread pool "blocking".
  */
final class Sqlite(path: String):
  private val url = s"jdbc:sqlite:$path"

  private def connection: Resource[IO, Connection] =
    Resource.fromAutoCloseable(IO.blocking(DriverManager.getConnection(url)))

  def update(sql: String)(bind: PreparedStatement => Unit = _ => ()): IO[Int] =
    connection.use(c => IO.blocking(Using.resource(c.prepareStatement(sql)) { st => bind(st); st.executeUpdate() }))

  def query[A](sql: String)(bind: PreparedStatement => Unit = _ => ())(read: ResultSet => A): IO[List[A]] =
    connection.use { c =>
      IO.blocking(Using.resource(c.prepareStatement(sql)) { st =>
        bind(st)
        Using.resource(st.executeQuery()) { rs =>
          val out = List.newBuilder[A]
          while rs.next() do out += read(rs)
          out.result()
        }
      })
    }
