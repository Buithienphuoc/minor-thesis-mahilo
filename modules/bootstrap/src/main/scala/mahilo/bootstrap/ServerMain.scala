package mahilo.bootstrap

import cats.effect.{ExitCode, IO, IOApp, Resource}
import dev.langchain4j.model.chat.ChatModel
import mahilo.adapters.http.{HttpServer, WebSocketHumanGateway}
import mahilo.adapters.langchain4j.{ChatModelFromLlmClient, LangChain4jLlmClient, LangChain4jModels, LangGraph4jEngine}
import mahilo.adapters.llm.anthropic.{AnthropicLlmClient, AnthropicSettings}
import mahilo.adapters.llm.openai.OpenAiCompatibleLlmClient
import mahilo.adapters.local.{HmacEnvelopeSigner, InMemoryTelemetry}
import mahilo.adapters.persistence.sqlite.{SqliteMessageStore, SqliteSessionRepository}
import mahilo.adapters.remote.{RemoteAgentEngine, RemoteEngineRoutes, RemoteTurnRegistry}
import mahilo.application.{Mahilo, MahiloConfig, MahiloPorts}
import mahilo.application.port.out.{AgentEngine, EnvelopeSigner, LlmClient}
import mahilo.application.service.AgentDefinition
import mahilo.bootstrap.templates.{CentralizedTemplates, TeamOfAgentsTemplates}
import mahilo.domain.AgentName

/** COMPOSITION ROOT cho server thật. Python: `run_server.py` của từng ví dụ, `control_plane.py`.
  *
  * Đây là nơi DUY NHẤT biết hết các adapter. Đổi công nghệ = sửa vài dòng ở đây, lõi
  * không đổi. Cấu hình bằng biến môi trường:
  *
  *   MAHILO_EXAMPLE      dispatcher (mặc định) | team   — chọn bộ agent (xem docs/user-guide.md)
  *   MAHILO_LLM          openai (mặc định) | anthropic (Claude) | langchain4j | scripted (không cần key, "bộ não" giả lập)
  *   MAHILO_ENGINE       native (mặc định) | langgraph4j   — engine mặc định cho agent (ví dụ dispatcher)
  *   OPENAI_API_KEY      key cho openai/langchain4j
  *   ANTHROPIC_API_KEY   key cho anthropic
  *   MAHILO_LLM_MODEL    mặc định gpt-4o-mini (openai) / claude-opus-5-5 (anthropic)
  *   MAHILO_LLM_EFFORT   (anthropic) low | medium | high | xhigh | max; bỏ trống = mặc định của model
  *   MAHILO_LLM_FALLBACKS (anthropic) true (mặc định) | false: tự chuyển model dự phòng khi Claude từ chối
  *   MAHILO_LLM_BASE_URL mặc định https://api.openai.com/v1 (đổi để dùng Ollama, vLLM...)
  *   MAHILO_DB           file SQLite, mặc định mahilo.db
  *   MAHILO_SECRET_KEY   bật ký HMAC cho phong bì
  *   MAHILO_POLICIES     danh sách policy chuẩn cần bật, vd "anti_loop,message_length"
  *   PORT                mặc định 8000
  *
  *   MAHILO_PYDANTICAI_URL    bật engine PydanticAI (service Python), vd http://localhost:8001
  *   MAHILO_PYDANTICAI_AGENTS agent nào dùng engine đó; mặc định "plumber" (dispatcher) / "ProductAgent" (team)
  *   MAHILO_PUBLIC_URL        URL engine dùng để gọi ngược về Mahilo, mặc định http://localhost:PORT
  *
  * Các biến này cũng đọc được từ file `.env` ở thư mục chạy (xem `DotEnv` và `.env.example`).
  */
object ServerMain extends IOApp:

  private val dotEnv = DotEnv.load(sys.env.getOrElse("MAHILO_ENV_FILE", ".env"))

  /** Biến môi trường thật được ưu tiên; không có thì lấy từ file `.env`. */
  private def env(name: String): Option[String] = sys.env.get(name).filter(_.nonEmpty).orElse(dotEnv.get(name).filter(_.nonEmpty))

  def run(args: List[String]): IO[ExitCode] =
    app.use(_ => IO.println("Mahilo-Scala đang chạy. Ctrl+C để dừng.") *> IO.never).as(ExitCode.Success)

  private def app: Resource[IO, Unit] =
    val example  = env("MAHILO_EXAMPLE").getOrElse("dispatcher")
    val provider = env("MAHILO_LLM").getOrElse("openai")
    val model    = env("MAHILO_LLM_MODEL").getOrElse(if provider == "anthropic" then "claude-opus-5-5" else "gpt-4o-mini")
    val baseUrl  = env("MAHILO_LLM_BASE_URL")
    val apiKey   = env("OPENAI_API_KEY")
    val port     = env("PORT").flatMap(_.toIntOption).getOrElse(8000)
    val dbPath   = env("MAHILO_DB").getOrElse("mahilo.db")
    val scripted = provider == "scripted"

    def requireKey: IO[String] =
      IO.fromOption(apiKey)(IllegalStateException("Thiếu OPENAI_API_KEY (hoặc đặt MAHILO_LLM=scripted để chạy thử)"))

    // ---- Port LlmClient ----
    val llm: Resource[IO, LlmClient] = provider match
      case "scripted"    => Resource.pure(DemoBrain.llm)
      case "anthropic" =>
        val key = IO.fromOption(env("ANTHROPIC_API_KEY"))(IllegalStateException("Thiếu ANTHROPIC_API_KEY (đặt trong biến môi trường hoặc file .env)"))
        val settings = AnthropicSettings(
          model = model,
          effort = env("MAHILO_LLM_EFFORT"),
          fallbacks = !env("MAHILO_LLM_FALLBACKS").contains("false")
        )
        Resource.eval(key).flatMap(k => AnthropicLlmClient.resource(settings, Some(k), env("MAHILO_ANTHROPIC_BASE_URL")))
      case "langchain4j" => Resource.eval(requireKey).map(k => LangChain4jLlmClient(LangChain4jModels.openAi(k, model, baseUrl)))
      case _ =>
        Resource.eval(requireKey).map(k => OpenAiCompatibleLlmClient(k, model, baseUrl.getOrElse("https://api.openai.com/v1")))

    // ---- Engine LangGraph4j: cần một ChatModel của LangChain4j ----
    def langGraphEngine(llm: LlmClient): Resource[IO, AgentEngine] =
      val chatModel: Resource[IO, ChatModel] =
        // Claude và bộ não giả lập: bọc chính LlmClient thành ChatModel của LangChain4j.
        if scripted || provider == "anthropic" then ChatModelFromLlmClient.resource(llm)
        else Resource.eval(requireKey).map(k => LangChain4jModels.openAi(k, model, baseUrl))
      chatModel.flatMap(LangGraph4jEngine.resource(_))

    // ---- Engine PydanticAI (service Python, qua REST) ----
    def pydanticAiEngine(registry: RemoteTurnRegistry): Option[AgentEngine] =
      env("MAHILO_PYDANTICAI_URL").map(url =>
        RemoteAgentEngine(url, env("MAHILO_PUBLIC_URL").getOrElse(s"http://localhost:$port"), registry)
      )

    def pydanticAiAgents(default: String): Set[String] =
      env("MAHILO_PYDANTICAI_AGENTS").getOrElse(default).split(",").map(_.trim).filter(_.nonEmpty).toSet

    // ---- Chọn ví dụ ----
    def buildExample(llm: LlmClient, registry: RemoteTurnRegistry): Resource[IO, Example] = example match
      case "team" =>
        // control_plane.py: Product = PydanticAI, Marketing = LangGraph, Sales = BaseAgent.
        langGraphEngine(llm).evalMap { marketing =>
          val remote  = pydanticAiEngine(registry).filter(_ => pydanticAiAgents("ProductAgent").contains("ProductAgent"))
          val warning =
            if remote.isEmpty then IO.println("⚠️  Chưa đặt MAHILO_PYDANTICAI_URL: ProductAgent tạm chạy bằng engine gốc.")
            else IO.unit
          warning.as(Example(TeamOfAgentsTemplates.definitions(Some(marketing), remote), TeamOfAgentsTemplates.startActive))
        }
      case "dispatcher" =>
        val default: Resource[IO, Option[AgentEngine]] =
          if env("MAHILO_ENGINE").contains("langgraph4j") then langGraphEngine(llm).map(Some(_)) else Resource.pure(None)
        default.map { engineOpt =>
          val remoteNames = pydanticAiAgents("plumber")
          val remote      = pydanticAiEngine(registry)
          val agents = CentralizedTemplates.all.map { spec =>
            val e = remote.filter(_ => remoteNames.contains(spec.name.value)).orElse(engineOpt)
            AgentDefinition(spec, engine = e)
          }
          Example(agents, List(CentralizedTemplates.dispatcher.name))
        }
      case other =>
        Resource.eval(IO.raiseError(IllegalArgumentException(s"MAHILO_EXAMPLE không hợp lệ: '$other' (dùng dispatcher | team)")))

    val cliHint =
      if sys.props.getOrElse("os.name", "").toLowerCase.contains("win") then
        "powershell -ExecutionPolicy Bypass -File scripts\\mahilo-connect.ps1 --agent-name <agent>"
      else "scripts/mahilo-connect.sh --agent-name <agent>"

    val signer: EnvelopeSigner = env("MAHILO_SECRET_KEY").fold(EnvelopeSigner.Disabled)(HmacEnvelopeSigner(_))

    for
      llmClient <- llm
      registry  <- Resource.eval(RemoteTurnRegistry.make)
      ex        <- buildExample(llmClient, registry)
      store     <- Resource.eval(SqliteMessageStore.make(dbPath))
      sessions  <- Resource.eval(SqliteSessionRepository.make(dbPath))
      telemetry <- Resource.eval(InMemoryTelemetry.make)
      gateway   <- Resource.eval(WebSocketHumanGateway.make)
      ports      = MahiloPorts(llmClient, store, sessions, gateway, telemetry, signer)
      mahilo    <- Mahilo.resource(ex.agents, ex.startActive, ports, MahiloConfig())
      _         <- Resource.eval(
                     env("MAHILO_POLICIES").toList.flatMap(_.split(",").map(_.trim)).filter(_.nonEmpty)
                       .foldLeft(IO.unit)((io, p) => io *> mahilo.admin.enablePolicy(p))
                   )
      server    <- HttpServer.resource(mahilo, gateway, "0.0.0.0", port, extraRoutes = RemoteEngineRoutes(registry).routes)
      _         <- Resource.eval(IO.println(
                     s"Ví dụ: $example  •  LLM: $provider${if scripted then "" else s" ($model)"}  •  agent: ${ex.agents.map(_.spec.name.value).mkString(", ")}  •  active: ${ex.startActive.map(_.value).mkString(", ")}\n" +
                       s"HTTP: ${server.baseUri}  •  WebSocket: ws://localhost:$port/ws/<agent>  •  CLI: $cliHint"
                   ))
    yield ()
