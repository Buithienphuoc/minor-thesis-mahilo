package mahilo.bootstrap

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite
import mahilo.adapters.langchain4j.{ChatModelFromLlmClient, LangGraph4jEngine}
import mahilo.adapters.local.ScriptedLlmClient
import mahilo.application.port.in.ChatOutcome
import mahilo.application.port.out.{LlmReply, MessageQuery}
import mahilo.application.service.AgentDefinition
import mahilo.bootstrap.TestKit.*
import mahilo.bootstrap.templates.CentralizedTemplates
import mahilo.domain.{AgentName, MessageId}
import mahilo.domain.conversation.ChatMessage
import mahilo.domain.messaging.*
import mahilo.domain.policy.{Policy, PolicyRule}

/** Test luồng hội thoại đầu-cuối qua lõi, với adapter in-memory và LLM giả lập. */
class ConversationFlowSpec extends CatsEffectSuite:

  /** Kịch bản 6.1: khách → dispatcher → plumber → thợ → plumber → dispatcher → khách. */
  private def runScenario(sys: TestSystem): IO[Unit] =
    for
      r1 <- sys.mahilo.chat.chat(dispatcher, "Toilet nhà tôi bị tắc")
      _   = assert(r1.isInstanceOf[ChatOutcome.Replied], r1)
      d1 <- eventually(sys.human.delivered)(_.size == 1)
      _   = assertEquals(d1.head._1, plumber)
      _   = assert(d1.head._2.contains("Toilet nhà tôi bị tắc"))
      _  <- sys.mahilo.chat.chat(plumber, "Chiều nay 3h tôi đến được")
      d2 <- eventually(sys.human.delivered)(_.size == 2)
      _   = assertEquals(d2(1)._1, dispatcher)
      _   = assert(d2(1)._2.contains("3h"))
      ms <- eventually(sys.store.query(MessageQuery()))(_.forall(_.state == DeliveryState.Processed))
      _   = assertEquals(ms.size, 2)
    yield ()

  test("kịch bản Dispatcher ↔ Plumber với engine gốc") {
    system().use(runScenario)
  }

  test("kịch bản Dispatcher ↔ Plumber khi plumber chạy bằng LangGraph4j") {
    val agents = for
      model  <- ChatModelFromLlmClient.resource(DemoBrain.llm)
      engine <- LangGraph4jEngine.resource(model)
    yield List(
      AgentDefinition(CentralizedTemplates.dispatcher),
      AgentDefinition(CentralizedTemplates.plumber, engine = Some(engine))
    )
    agents.flatMap(as => system(agents = as)).use { sys =>
      runScenario(sys) *>
        sys.sessions.load(plumber).map { s =>
          // Session của plumber có đủ: tin hàng đợi, lời gọi tool, kết quả tool, câu trả lời
          assert(s.messages.exists(_.isInstanceOf[ChatMessage.Tool]), s.messages)
          assert(s.messages.collect { case ChatMessage.User(c) => c }.exists(_.startsWith("Pending messages: dispatcher")))
        }
    }
  }

  test("chat với agent chưa active hoặc không tồn tại") {
    system().use { sys =>
      for
        a <- sys.mahilo.chat.chat(plumber, "hello")
        b <- sys.mahilo.chat.chat(AgentName("ghost"), "hello")
      yield
        assertEquals(a, ChatOutcome.AgentInactive(plumber))
        assertEquals(b, ChatOutcome.AgentNotFound(AgentName("ghost")))
    }
  }

  test("policy chặn tin: LLM nhận ngay lý do, tin không vào hàng đợi, vi phạm được ghi lại") {
    val brain = ScriptedLlmClient { req =>
      if req.messages.lastOption.exists(_.isInstanceOf[ChatMessage.Tool]) then
        val ChatMessage.Tool(_, _, out) = req.messages.last: @unchecked
        IO.pure(LlmReply(Some(out))) // trả nguyên kết quả tool ra để test kiểm tra
      else ScriptedLlmClient.Script.call("chat_with_agent", "agent_name" -> "plumber", "question" -> "hi")
    }
    system(llm = brain).use { sys =>
      for
        _  <- sys.mahilo.admin.enablePolicy("message_length")
        r  <- sys.mahilo.chat.chat(dispatcher, "giúp tôi")
        ms <- sys.store.query(MessageQuery())
        vs <- sys.mahilo.observe.violations(10, None)
      yield
        val ChatOutcome.Replied(text, _) = r: @unchecked
        assert(text.contains("rejected due to policy violations"), text)
        assert(text.contains("message_length"), text)
        assertEquals(ms, Nil)
        assertEquals(vs.map(_.policyName), List("message_length"))
    }
  }

  test("policy tự viết (heuristic) được thêm lúc chạy") {
    val noShouting = Policy(
      name = "no_shouting",
      description = "Không viết toàn chữ hoa",
      rule = PolicyRule.Heuristic((e, _) => Option.when(e.payload.exists(_.isLetter) && e.payload == e.payload.toUpperCase)("Don't shout")),
      enabled = true
    )
    val brain = ScriptedLlmClient { req =>
      req.messages.lastOption match
        case Some(ChatMessage.Tool(_, _, out)) => IO.pure(LlmReply(Some(out)))
        case _ => ScriptedLlmClient.Script.call("chat_with_agent", "agent_name" -> "plumber", "question" -> "COME NOW PLEASE")
    }
    system(llm = brain).use { sys =>
      sys.mahilo.admin.addPolicy(noShouting) *> sys.mahilo.chat.chat(dispatcher, "x").map {
        case ChatOutcome.Replied(text, _) => assert(text.contains("Don't shout"), text)
        case other                        => fail(other.toString)
      }
    }
  }

  test("xử lý hộp thư lỗi: thử lại 3 lần, rồi Failed và gửi tin ERROR về người gửi") {
    val brain = ScriptedLlmClient { req =>
      if ScriptedLlmClient.Script.agentName(req).contains("plumber") then IO.raiseError(RuntimeException("LLM down"))
      else DemoBrain.reply(req)
    }
    system(llm = brain).use { sys =>
      for
        _   <- sys.mahilo.chat.chat(dispatcher, "Toilet nhà tôi bị tắc")
        all <- eventually(sys.store.query(MessageQuery()))(_.exists(_.envelope.kind == MessageKind.Error))
        tel <- sys.telemetry.snapshot(None)
      yield
        val original = all.find(_.envelope.sender == dispatcher).get
        assertEquals(original.state, DeliveryState.Failed(4))
        val error = all.find(_.envelope.kind == MessageKind.Error).get
        assertEquals(error.envelope.recipient, dispatcher)
        assertEquals(error.envelope.replyTo, Some(original.envelope.id))
        assertEquals(tel.counters.get("MessageRetried"), Some(3L))
    }
  }

  test("tin sai chữ ký bị đánh dấu Failed (Python để pending vĩnh viễn)") {
    val forged = Envelope(MessageId("forged"), AgentName("plumber"), dispatcher, MessageKind.Direct, "fake", java.time.Instant.now(), signature = Some("bad"))
    system(preload = _.save(forged)).use { sys =>
      eventually(sys.store.get(MessageId("forged")))(_.exists(_.state.isInstanceOf[DeliveryState.Failed])).void
    }
  }

  test("tool lỗi vẫn có tool result; tool không tồn tại không làm sập lượt; giới hạn số vòng") {
    val calls = Ref.unsafe[IO, Int](0)
    val brain = ScriptedLlmClient { _ =>
      calls.update(_ + 1) *> ScriptedLlmClient.Script.call("does_not_exist", "x" -> "1") // luôn đòi gọi tool
    }
    system(llm = brain).use { sys =>
      for
        r <- sys.mahilo.chat.chat(dispatcher, "hello")
        s <- sys.sessions.load(dispatcher)
        n <- calls.get
      yield
        val ChatOutcome.Replied(text, _) = r: @unchecked
        assert(text.contains("quá số vòng"), text)
        assertEquals(n, 10)
        val toolResults = s.messages.collect { case ChatMessage.Tool(_, _, c) => c }
        assertEquals(toolResults.size, 10)
        assert(toolResults.forall(_.contains("unknown tool")))
    }
  }

  test("agent không được nhắn cho chính mình, và không được nhắn ngoài can_contact") {
    val brain = ScriptedLlmClient { req =>
      req.messages.lastOption match
        case Some(ChatMessage.Tool(_, _, out)) => IO.pure(LlmReply(Some(out)))
        case _ => ScriptedLlmClient.Script.call("chat_with_agent", "agent_name" -> "dispatcher", "question" -> "hello me")
    }
    system(llm = brain).use { sys =>
      sys.mahilo.chat.chat(dispatcher, "x").map {
        case ChatOutcome.Replied(text, _) => assert(text.contains("cannot send a message to yourself"), text)
        case other                        => fail(other.toString)
      }
    }
  }
