package mahilo.domain

import java.time.Instant
import mahilo.domain.agent.{AgentSpec, ContactRule}
import mahilo.domain.conversation.{ChatMessage, Session}
import mahilo.domain.messaging.*
import mahilo.domain.policy.*

/** Test cho phần domain thuần: không IO, không mock, chỉ gọi hàm và so kết quả. */
class DomainSpec extends munit.FunSuite:

  private val a = AgentName("a")
  private val b = AgentName("b")
  private var seq = 0
  private def env(from: AgentName, to: AgentName, text: String): Envelope =
    seq += 1
    Envelope(MessageId(s"m$seq"), from, to, MessageKind.Direct, text, Instant.ofEpochSecond(seq.toLong))

  test("Session.lastN lấy thêm 1 tin nếu ra số lẻ (giống get_last_n_messages của Python)") {
    val s = Session(a, Vector.tabulate(10)(i => ChatMessage.User(s"m$i")))
    assertEquals(s.lastN(7).size, 8)
    assertEquals(s.lastN(4).size, 4)
    assertEquals(Session(a, Vector(ChatMessage.User("x"))).lastN(7).size, 1)
  }

  test("RetryPolicy: thử lại tối đa 3 lần rồi Failed") {
    val r = RetryPolicy(3)
    val states = Iterator.iterate(DeliveryState.New)(r.afterFailure).take(5).toList
    assertEquals(states, List(DeliveryState.Pending(0), DeliveryState.Pending(1), DeliveryState.Pending(2), DeliveryState.Pending(3), DeliveryState.Failed(4)))
  }

  test("TextSimilarity cho cùng kết quả với difflib.SequenceMatcher của Python") {
    assertEqualsDouble(TextSimilarity.ratio("abcd", "bcde"), 0.75, 1e-9)
    assertEqualsDouble(TextSimilarity.ratio("Please check the pipe now", "Please check the pipes now"), 0.9803921568627451, 1e-9)
    assertEqualsDouble(TextSimilarity.ratio("hello world", "goodbye moon"), 0.2608695652173913, 1e-9)
    assertEqualsDouble(TextSimilarity.ratio("", ""), 1.0, 1e-9)
  }

  test("message_length chặn tin quá ngắn và quá dài") {
    val PolicyRule.Heuristic(check) = StandardPolicies.messageLength.rule: @unchecked
    val ctx = PolicyContext(Nil, Instant.EPOCH)
    assert(check(env(a, b, "hi"), ctx).isDefined)
    assert(check(env(a, b, "x" * 4001), ctx).isDefined)
    assert(check(env(a, b, "Toilet is clogged, please help"), ctx).isEmpty)
  }

  test("anti_loop so với tin MỚI NHẤT của người gửi (Python so nhầm với tin cũ nhất)") {
    val history = List(
      env(a, b, "Can you come at 3pm today?"),         // cũ nhất
      env(b, a, "Which address?"),
      env(a, b, "The address is 12 Nguyen Hue street"),
      env(b, a, "OK noted")                             // mới nhất
    )
    val ctx = PolicyContext(history, Instant.EPOCH)
    // Lặp lại gần y hệt tin MỚI NHẤT của a → bị chặn
    assert(StandardPolicies.antiLoopCheck(env(a, b, "The address is 12 Nguyen Hue street!"), ctx).isDefined)
    // Lặp lại tin CŨ NHẤT của a → không bị chặn bởi luật "giống tin trước"
    assert(StandardPolicies.antiLoopCheck(env(a, b, "Can you come at 3pm today?"), ctx).isEmpty)
  }

  test("NaturalLanguageJudge.parse đọc đúng định dạng COMPLIANCE/REASON") {
    assertEquals(NaturalLanguageJudge.parse("COMPLIANCE: YES\nREASON: fine"), None)
    assertEquals(NaturalLanguageJudge.parse("COMPLIANCE: NO\nREASON: off-topic\nmore"), Some("off-topic"))
    assertEquals(NaturalLanguageJudge.parse("yes, looks good"), None)
    assertEquals(NaturalLanguageJudge.parse("No.\nIt is rude"), Some("It is rude"))
  }

  test("PolicySet luôn sắp theo priority giảm dần, bật/tắt theo tên") {
    val set = StandardPolicies.all.setEnabled("message_length", true)
    assertEquals(set.all.map(_.priority), List(100, 90, 70, 50))
    assertEquals(set.enabled.map(_.name), List("message_length"))
  }

  test("AgentSpec.contactable: Everyone = mọi agent trừ chính mình; Only = danh sách") {
    val x = AgentSpec(AgentName("x"), "k", "d")
    val y = AgentSpec(AgentName("y"), "k", "d")
    val z = AgentSpec(AgentName("z"), "k", "d", canContact = ContactRule.Only(Set(AgentName("y"))))
    val all = List(x, y, z)
    assertEquals(x.contactable(all).map(_.name.value), List("y", "z"))
    assertEquals(z.contactable(all).map(_.name.value), List("y"))
  }
