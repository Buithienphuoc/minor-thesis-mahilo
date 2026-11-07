package mahilo.bootstrap

import munit.CatsEffectSuite
import mahilo.adapters.langchain4j.{ChatModelFromLlmClient, LangGraph4jEngine}
import mahilo.application.port.in.ChatOutcome
import mahilo.application.port.out.MessageQuery
import mahilo.bootstrap.TestKit.*
import mahilo.bootstrap.templates.TeamOfAgentsTemplates as Team
import mahilo.domain.conversation.ChatMessage
import mahilo.domain.messaging.DeliveryState

/** Ví dụ team_of_agents (Python: examples/team_of_agents). Ba agent bật sẵn,
  * MarketingAgent chạy bằng LangGraph4j như bản gốc chạy bằng LangGraph.
  * (ProductAgent qua PydanticAI REST đã có RemoteEngineSpec và test end-to-end với service Python.)
  */
class TeamOfAgentsSpec extends CatsEffectSuite:

  private val teamSystem =
    for
      model  <- ChatModelFromLlmClient.resource(DemoBrain.llm)
      engine <- LangGraph4jEngine.resource(model)
      sys    <- system(
                  agents = Team.definitions(Some(engine), None),
                  startActive = Team.startActive,
                  humans = Set(Team.Sales, Team.Marketing, Team.Product)
                )
    yield sys

  test("cả ba agent đều active ngay khi khởi động") {
    teamSystem.use { sys =>
      sys.mahilo.observe.agents.map { as =>
        assertEquals(as.filter(_.active).map(_.name).toSet, Set(Team.Sales, Team.Marketing, Team.Product))
      }
    }
  }

  test("kịch bản 1: SalesAgent phân tích cuộc gọi, hỏi ProductAgent, nhận trả lời và báo người dùng") {
    teamSystem.use { sys =>
      for
        r1 <- sys.mahilo.chat.chat(Team.Sales, "Phân tích các cuộc gọi bán hàng gần đây giúp mình")
        _   = r1 match
                case ChatOutcome.Replied(t, _) => assert(t.contains("mahilo integration"), t)
                case other                     => fail(other.toString)
        _  <- sys.mahilo.chat.chat(Team.Sales, "Ok, hỏi product đi")
        d  <- eventually(sys.human.delivered)(_.nonEmpty)
        ms <- eventually(sys.store.query(MessageQuery()))(m => m.size == 2 && m.forall(_.state == DeliveryState.Processed))
        ps <- sys.sessions.load(Team.Product)
      yield
        assertEquals(d.head._1, Team.Sales)
        assert(d.head._2.startsWith("ProductAgent trả lời:"), d)
        assertEquals(ms.map(m => m.envelope.sender -> m.envelope.recipient).toSet,
                     Set(Team.Sales -> Team.Product, Team.Product -> Team.Sales))
        assert(ps.messages.exists { case ChatMessage.Tool(_, "analyze_feature_request", _) => true; case _ => false })
    }
  }

  test("kịch bản 2: MarketingAgent (LangGraph4j) lên lịch nội dung, hỏi SalesAgent về lead, rồi đề xuất cho người dùng") {
    teamSystem.use { sys =>
      for
        r  <- sys.mahilo.chat.chat(Team.Marketing, "Sắp có tính năng mới, lên kế hoạch nội dung giúp mình")
        d  <- eventually(sys.human.delivered)(_.nonEmpty)
        ms <- eventually(sys.store.query(MessageQuery()))(m => m.size == 2 && m.forall(_.state == DeliveryState.Processed))
        mk <- sys.sessions.load(Team.Marketing)
        sl <- sys.sessions.load(Team.Sales)
      yield
        assert(r.isInstanceOf[ChatOutcome.Replied], r)
        assertEquals(d.head._1, Team.Marketing)
        assert(d.head._2.startsWith("Đề xuất:"), d)
        assert(d.head._2.contains("Reddit"), d) // dữ liệu từ analyze_lead_sources của SalesAgent
        val mkTools = mk.messages.collect { case ChatMessage.Tool(_, n, _) => n }
        assert(mkTools.containsSlice(List("research_trending_topics", "generate_content_calendar", "chat_with_agent")), mkTools)
        assert(sl.messages.exists { case ChatMessage.Tool(_, "analyze_lead_sources", _) => true; case _ => false })
    }
  }
