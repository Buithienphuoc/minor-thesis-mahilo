package mahilo.bootstrap

import java.nio.file.Files
import java.time.Instant
import cats.effect.IO
import munit.CatsEffectSuite
import mahilo.adapters.llm.openai.OpenAiJson
import mahilo.adapters.local.HmacEnvelopeSigner
import mahilo.adapters.persistence.sqlite.{SqliteMessageStore, SqliteSessionRepository}
import mahilo.application.port.out.{LlmRequest, MessageQuery}
import mahilo.domain.{AgentName, MessageId}
import mahilo.domain.conversation.{ChatMessage, Session, ToolCall}
import mahilo.domain.messaging.*
import mahilo.domain.tool.BuiltinTools

/** Test riêng từng adapter: mỗi adapter phải tuân đúng hợp đồng của port. */
class AdaptersSpec extends CatsEffectSuite:

  private val a = AgentName("a")
  private val b = AgentName("b")
  private def env(i: Int, from: AgentName, to: AgentName) =
    Envelope(MessageId(s"m$i"), from, to, MessageKind.Direct, s"payload $i", Instant.ofEpochSecond(1000L + i), Some("corr"))

  test("SqliteMessageStore: lưu, đọc pending, cập nhật trạng thái, conversation trả CŨ → MỚI") {
    val path = Files.createTempFile("mahilo", ".db").toString
    for
      store <- SqliteMessageStore.make(path)
      _     <- store.save(env(1, a, b))
      _     <- store.save(env(2, b, a))
      _     <- store.save(env(3, a, b))
      _     <- store.updateState(MessageId("m1"), DeliveryState.Processed)
      pend  <- store.pending(b)
      conv  <- store.conversation(a, b, limit = 2)
      who   <- store.recipientsWithPending
      got   <- store.get(MessageId("m3"))
      recent <- store.query(MessageQuery(sender = Some(a), limit = 10))
    yield
      assertEquals(pend.map(_.envelope.id.value), List("m3"))
      assertEquals(conv.map(_.id.value), List("m2", "m3")) // 2 tin MỚI nhất, xếp cũ → mới
      assertEquals(who, Set(a, b))
      assertEquals(got.map(_.envelope), Some(env(3, a, b)))
      assertEquals(recent.map(_.envelope.id.value), List("m3", "m1")) // query: mới → cũ
  }

  test("SqliteSessionRepository: lưu và đọc lại đủ 4 loại tin") {
    val path = Files.createTempFile("mahilo", ".db").toString
    val msgs = Vector(
      ChatMessage.User("hi"),
      ChatMessage.Assistant(None, List(ToolCall("c1", "chat_with_agent", Map("agent_name" -> "b"), """{"agent_name":"b"}"""))),
      ChatMessage.Tool("c1", "chat_with_agent", "sent"),
      ChatMessage.Assistant(Some("done"))
    )
    for
      repo <- SqliteSessionRepository.make(path)
      _    <- repo.save(Session(a, msgs))
      back <- repo.load(a)
      none <- repo.load(b)
    yield
      assertEquals(back.messages, msgs)
      assertEquals(none.messages, Vector.empty)
  }

  test("OpenAiJson: dựng request có tools và đọc reply có tool_calls") {
    val req  = OpenAiJson.request(LlmRequest(List(ChatMessage.User("hi")), List(BuiltinTools.contactHuman)), "gpt-4o-mini")
    assertEquals(req.hcursor.downField("tools").downN(0).downField("function").downField("name").as[String], Right("contact_human"))
    val body =
      """{"choices":[{"message":{"role":"assistant","content":null,
        |"tool_calls":[{"id":"call_1","type":"function","function":{"name":"chat_with_agent",
        |"arguments":"{\"agent_name\":\"plumber\",\"question\":\"help\",\"n\":2}"}}]}}]}""".stripMargin
    val reply = OpenAiJson.reply(body).toOption.get
    assertEquals(reply.content, None)
    assertEquals(reply.toolCalls.head.arguments, Map("agent_name" -> "plumber", "question" -> "help", "n" -> "2"))
  }

  test("HmacEnvelopeSigner: ký rồi kiểm tra; sửa payload là hỏng chữ ký") {
    val signer = HmacEnvelopeSigner("secret")
    val signed = signer.sign(env(1, a, b))
    assert(signer.verify(signed))
    assert(!signer.verify(signed.copy(payload = "tampered")))
    assert(!HmacEnvelopeSigner("other").verify(signed))
    IO.unit
  }
