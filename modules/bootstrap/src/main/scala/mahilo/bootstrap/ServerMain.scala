package mahilo.bootstrap

import cats.effect.{ExitCode, IO, IOApp, Resource}
import mahilo.adapters.http.{HttpServer, WebSocketHumanGateway}
import mahilo.adapters.langchain4j.{LangChain4jLlmClient, LangChain4jModels, LangGraph4jEngine}
import mahilo.adapters.llm.openai.OpenAiCompatibleLlmClient
import mahilo.adapters.local.{HmacEnvelopeSigner, InMemoryTelemetry}
import mahilo.adapters.persistence.sqlite.{SqliteMessageStore, SqliteSessionRepository}
import mahilo.adapters.remote.{RemoteAgentEngine, RemoteEngineRoutes, RemoteTurnRegistry}
import mahilo.application.{Mahilo, MahiloConfig, MahiloPorts}
import mahilo.application.port.out.{AgentEngine, EnvelopeSigner, LlmClient}
import mahilo.application.service.AgentDefinition
import mahilo.bootstrap.templates.CentralizedTemplates

/** COMPOSITION ROOT cho server thật. Python: `examples/dispatcher/run_server.py`.
  *
  * Đây là nơi DUY NHẤT biết hết các adapter. Đổi công nghệ = sửa vài dòng ở đây, lõi
  * không đổi. Cấu hình bằng biến môi trường:
  *
  *   MAHILO_LLM          openai (mặc định) | langchain4j | scripted (không cần key, dùng DemoBrain)
  *   MAHILO_ENGINE       native (mặc định) | langgraph4j   — engine cho mọi agent
  *   OPENAI_API_KEY      key cho openai/langchain4j
  *   MAHILO_LLM_MODEL    mặc định gpt-4o-mini
  *   MAHILO_LLM_BASE_URL mặc định https://api.openai.com/v1 (đổi để dùng Ollama, vLLM...)
  *   MAHILO_DB           file SQLite, mặc định mahilo.db
  *   MAHILO_SECRET_KEY   bật ký HMAC cho phong bì
  *   MAHILO_POLICIES     danh sách policy chuẩn cần bật, vd "anti_loop,message_length"
  *   PORT                mặc định 8000
  *
  *   MAHILO_PYDANTICAI_URL    bật engine PydanticAI (service Python), vd http://localhost:8001
  *   MAHILO_PYDANTICAI_AGENTS agent nào dùng engine đó, mặc định "plumber"
  *   MAHILO_PUBLIC_URL        URL engine dùng để gọi ngược về Mahilo, mặc định http://localhost:PORT
  */
object ServerMain extends IOApp:

  private def env(name: String): Option[String] = sys.env.get(name).filter(_.nonEmpty)

  def run(args: List[String]): IO[ExitCode] =
    app.use(_ => IO.println("Mahilo-Scala đang chạy. Ctrl+C để dừng.") *> IO.never).as(ExitCode.Success)

  private def app: Resource[IO, Unit] =
    val model   = env("MAHILO_LLM_MODEL").getOrElse("gpt-4o-mini")
    val baseUrl = env("MAHILO_LLM_BASE_URL")
    val apiKey  = env("OPENAI_API_KEY")
    val port    = env("PORT").flatMap(_.toIntOption).getOrElse(8000)
    val dbPath  = env("MAHILO_DB").getOrElse("mahilo.db")

    def requireKey: IO[String] =
      IO.fromOption(apiKey)(IllegalStateException("Thiếu OPENAI_API_KEY (hoặc đặt MAHILO_LLM=scripted để chạy thử)"))

    // ---- Chọn adapter cho port LlmClient ----
    val llm: Resource[IO, LlmClient] = env("MAHILO_LLM").getOrElse("openai") match
      case "scripted"    => Resource.pure(DemoBrain.llm)
      case "langchain4j" => Resource.eval(requireKey).map(k => LangChain4jLlmClient(LangChain4jModels.openAi(k, model, baseUrl)))
      case _ =>
        Resource.eval(requireKey).map(k => OpenAiCompatibleLlmClient(k, model, baseUrl.getOrElse("https://api.openai.com/v1")))

    // ---- Chọn engine (vòng lặp gốc hay LangGraph4j) ----
    def engine(llm: LlmClient): Resource[IO, Option[AgentEngine]] = env("MAHILO_ENGINE") match
      case Some("langgraph4j") =>
        val chatModel = env("MAHILO_LLM") match
          case Some("scripted") => mahilo.adapters.langchain4j.ChatModelFromLlmClient.resource(llm)
          case _                => Resource.eval(requireKey).map(k => LangChain4jModels.openAi(k, model, baseUrl))
        chatModel.flatMap(LangGraph4jEngine.resource(_)).map(Some(_))
      case _ => Resource.pure(None)

    val signer: EnvelopeSigner = env("MAHILO_SECRET_KEY").fold(EnvelopeSigner.Disabled)(HmacEnvelopeSigner(_))

    for
      llmClient <- llm
      engineOpt <- engine(llmClient)
      store     <- Resource.eval(SqliteMessageStore.make(dbPath))
      sessions  <- Resource.eval(SqliteSessionRepository.make(dbPath))
      telemetry <- Resource.eval(InMemoryTelemetry.make)
      gateway   <- Resource.eval(WebSocketHumanGateway.make)
      registry  <- Resource.eval(RemoteTurnRegistry.make)
      // Agent nào được chỉ định thì giao cho engine PydanticAI (Python) qua REST.
      remote     = env("MAHILO_PYDANTICAI_URL").map { url =>
                     val names = env("MAHILO_PYDANTICAI_AGENTS").getOrElse("plumber").split(",").map(_.trim).toSet
                     val self  = env("MAHILO_PUBLIC_URL").getOrElse(s"http://localhost:$port")
                     names -> (RemoteAgentEngine(url, self, registry): AgentEngine)
                   }
      agents     = CentralizedTemplates.all.map { spec =>
                     val engine = remote.collect { case (names, e) if names.contains(spec.name.value) => e }.orElse(engineOpt)
                     AgentDefinition(spec, engine = engine)
                   }
      ports      = MahiloPorts(llmClient, store, sessions, gateway, telemetry, signer)
      mahilo    <- Mahilo.resource(agents, startActive = List(CentralizedTemplates.dispatcher.name), ports, MahiloConfig())
      _         <- Resource.eval(
                     env("MAHILO_POLICIES").toList.flatMap(_.split(",").map(_.trim)).filter(_.nonEmpty)
                       .foldLeft(IO.unit)((io, p) => io *> mahilo.admin.enablePolicy(p))
                   )
      server    <- HttpServer.resource(mahilo, gateway, "0.0.0.0", port, extraRoutes = RemoteEngineRoutes(registry).routes)
      _         <- Resource.eval(IO.println(s"HTTP: ${server.baseUri}  •  WebSocket: ws://localhost:$port/ws/<agent>"))
    yield ()
