package mahilo.bootstrap

import scala.concurrent.duration.*
import cats.effect.{IO, Ref, Resource}
import com.comcast.ip4s.{Host, Port}
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.HttpRoutes
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import com.anthropic.core.ObjectMappers
import com.anthropic.models.messages.Message
import mahilo.adapters.langchain4j.{ChatModelFromLlmClient, LangGraph4jEngine}
import mahilo.adapters.llm.anthropic.{AnthropicConversions, AnthropicLlmClient, AnthropicSettings}
import mahilo.application.port.in.ChatOutcome
import mahilo.application.service.AgentDefinition
import mahilo.bootstrap.TestKit.*
import mahilo.bootstrap.templates.CentralizedTemplates

/** Adapter Claude (SDK Java chính thức) nói chuyện với một server Messages API GIẢ, để kiểm tra
  * đúng định dạng gửi đi mà không cần API key: không có `temperature`, `system` tách riêng,
  * kết quả tool nằm trong tin `user`, và block `thinking` được gửi lại nguyên văn trong vòng lặp tool.
  */
class AnthropicSpec extends CatsEffectSuite:

  override def munitIOTimeout = 30.seconds

  private val toolUseReply =
    """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
      |"content":[{"type":"thinking","thinking":"","signature":"sig-abc"},
      |{"type":"text","text":"Để mình xem."},
      |{"type":"tool_use","id":"toolu_1","name":"does_not_exist","input":{"x":"1","n":2}}],
      |"stop_reason":"tool_use","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5}}""".stripMargin

  private val textReply =
    """{"id":"msg_2","type":"message","role":"assistant","model":"claude-opus-5-5",
      |"content":[{"type":"text","text":"Đã xong."}],
      |"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5}}""".stripMargin

  /** Server giả: có tool_result trong tin cuối thì trả lời bằng chữ, không thì gọi tool. Ghi lại mọi request. */
  private def fakeApi(port: Int, seen: Ref[IO, Vector[(Json, Option[String])]]): Resource[IO, Unit] =
    val routes = HttpRoutes.of[IO] { case req @ POST -> Root / "v1" / "messages" =>
      req.as[Json].flatMap { body =>
        val beta = req.headers.get(org.typelevel.ci.CIString("anthropic-beta")).map(_.head.value)
        val lastHasToolResult = body.hcursor.downField("messages").values.flatMap(_.lastOption)
          .flatMap(_.hcursor.downField("content").values).exists(_.exists(_.hcursor.get[String]("type").contains("tool_result")))
        seen.update(_ :+ (body -> beta)) *> Ok(parse(if lastHasToolResult then textReply else toolUseReply).toOption.get)
      }
    }
    EmberServerBuilder.default[IO].withHost(Host.fromString("127.0.0.1").get).withPort(Port.fromInt(port).get)
      .withShutdownTimeout(Duration.Zero).withHttpApp(routes.orNotFound).build.map(_ => ())

  private def claude(port: Int) =
    AnthropicLlmClient.resource(AnthropicSettings(), Some("test-key"), Some(s"http://127.0.0.1:$port"))

  private def checkSecondRequest(seen: Vector[(Json, Option[String])]): Unit =
    assertEquals(seen.size, 2, seen)
    val (first, beta) = seen.head
    val (second, _)   = seen(1)
    assert(first.hcursor.downField("temperature").failed, "không được gửi temperature")
    assertEquals(first.hcursor.get[String]("model"), Right("claude-opus-5-5"))
    assert(first.hcursor.get[String]("system").exists(_.contains("dispatcher")), first)
    assertEquals(first.hcursor.downField("thinking").get[String]("type"), Right("adaptive"))
    assertEquals(first.hcursor.get[String]("fallbacks"), Right("default"))
    assert(beta.exists(_.contains("server-side-fallback-2026-07-01")), beta)
    val msgs = second.hcursor.downField("messages").values.get.toVector
    val assistant = msgs.find(_.hcursor.get[String]("role").contains("assistant")).get
    val blocks = assistant.hcursor.downField("content").values.get.toVector
    assertEquals(blocks.map(_.hcursor.get[String]("type").getOrElse("")), Vector("thinking", "text", "tool_use"))
    assertEquals(blocks.head.hcursor.get[String]("signature"), Right("sig-abc"))
    val last = msgs.last
    assertEquals(last.hcursor.get[String]("role"), Right("user"))
    assertEquals(last.hcursor.downField("content").downN(0).get[String]("tool_use_id"), Right("toolu_1"))

  test("engine gốc: vòng lặp tool với Claude gửi lại block thinking, kết quả tool nằm trong tin user") {
    val port = 24000 + scala.util.Random.nextInt(1000)
    (for
      seen <- Resource.eval(Ref.of[IO, Vector[(Json, Option[String])]](Vector.empty))
      _    <- fakeApi(port, seen)
      llm  <- claude(port)
      sys  <- system(llm = llm)
    yield (sys, seen)).use { (sys, seen) =>
      for
        r <- sys.mahilo.chat.chat(dispatcher, "Toilet nhà tôi bị tắc")
        s <- seen.get
      yield
        assertEquals(r.asInstanceOf[ChatOutcome.Replied].text, "Đã xong.")
        checkSecondRequest(s)
    }
  }

  test("engine LangGraph4j: Claude qua ChatModelFromLlmClient, thinking vẫn được gửi lại") {
    val port = 25000 + scala.util.Random.nextInt(1000)
    (for
      seen   <- Resource.eval(Ref.of[IO, Vector[(Json, Option[String])]](Vector.empty))
      _      <- fakeApi(port, seen)
      llm    <- claude(port)
      model  <- ChatModelFromLlmClient.resource(llm)
      engine <- LangGraph4jEngine.resource(model)
      sys    <- system(llm = llm, agents = List(
                  AgentDefinition(CentralizedTemplates.dispatcher, engine = Some(engine)),
                  AgentDefinition(CentralizedTemplates.plumber)))
    yield (sys, seen)).use { (sys, seen) =>
      for
        r <- sys.mahilo.chat.chat(dispatcher, "Toilet nhà tôi bị tắc")
        s <- seen.get
      yield
        assertEquals(r.asInstanceOf[ChatOutcome.Replied].text, "Đã xong.")
        checkSecondRequest(s)
    }
  }

  test("tham số tool giữ đúng kiểu; Claude từ chối thì trả câu xin lỗi thay vì lỗi") {
    val mapper = ObjectMappers.jsonMapper()
    val tool   = AnthropicConversions.reply(mapper.readValue(toolUseReply, classOf[Message]))
    assertEquals(tool.content, Some("Để mình xem."))
    assertEquals(tool.toolCalls.map(c => c.name -> c.arguments), List("does_not_exist" -> Map("x" -> "1", "n" -> "2")))
    val refusal = mapper.readValue(
      """{"id":"m","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":"refusal",
        |"stop_details":{"type":"refusal","category":"cyber","explanation":"policy"},"usage":{"input_tokens":1,"output_tokens":0}}""".stripMargin,
      classOf[Message])
    assert(AnthropicConversions.reply(refusal).content.exists(_.startsWith("Xin lỗi, Claude đã từ chối")))
  }

  test("DotEnv đọc KEY=value, bỏ chú thích, bỏ nháy, hiểu tiền tố export") {
    val m = DotEnv.parse(List("# chú thích", "", "MAHILO_LLM=anthropic", "export ANTHROPIC_API_KEY=\"sk-ant-x\"", "PORT=8000 # cổng", "BAD"))
    assertEquals(m, Map("MAHILO_LLM" -> "anthropic", "ANTHROPIC_API_KEY" -> "sk-ant-x", "PORT" -> "8000"))
  }
