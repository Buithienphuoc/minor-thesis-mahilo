// =====================================================================
//  mahilo-scala: port Mahilo (Python) sang Scala 3 theo Hexagonal Architecture
//
//  Chiều phụ thuộc (mũi tên = "phụ thuộc vào"):
//
//      bootstrap ──► adapter-* ──► application ──► domain
//
//  - domain:       thuần Scala, KHÔNG thư viện ngoài, KHÔNG IO.
//  - application:  use case + port (trait). Chỉ phụ thuộc domain và cats-effect.
//  - adapter-*:    hiện thực port bằng công nghệ cụ thể (HTTP, SQLite, OpenAI...).
//  - bootstrap:    "composition root": lắp ráp adapter vào port, chạy chương trình.
// =====================================================================

ThisBuild / scalaVersion := "3.3.4"
ThisBuild / organization := "io.mahilo"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalacOptions ++= Seq("-encoding", "utf8", "-deprecation", "-feature", "-unchecked", "-Wunused:imports")

val V = new {
  val catsEffect      = "3.5.7"
  val circe           = "0.14.10"
  val http4s          = "0.23.30"
  val sqlite          = "3.47.1.0"
  val logback         = "1.5.12"
  val munit           = "1.0.3"
  val munitCatsEffect = "2.0.0"
  val langchain4j     = "1.22.0" // LangChain4j: abstraction ChatModel, Tool... trên JVM
  val langgraph4j     = "1.9.5"  // LangGraph4j: StateGraph trên JVM (dùng langchain4j 1.22.0)
  val anthropic       = "3.3.0"  // SDK Java chính thức của Anthropic (Claude)
}

// Tuỳ chọn JVM cho chương trình chạy bằng `sbt run` (server, demo, CLI).
//  - UTF-8 cho tiếng Việt.
//  - Tắt cảnh báo nhầm của cats-effect "IOApp main is running on a thread other than the main thread"
//    (thấy trên JDK 25 khi chạy qua sbt; chương trình vẫn đúng vì `run` đã fork).
//  - JDK 17+: cho phép sqlite-jdbc nạp thư viện native mà không in cảnh báo.
//  - JDK 23+: cho phép Scala 3.3 dùng sun.misc.Unsafe (lazy val) mà không in cảnh báo.
lazy val javaMajor: Int = sys.props("java.specification.version").split('.').last.toInt
lazy val runJvmOptions: Seq[String] =
  Seq("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dstdin.encoding=UTF-8",
      "-Dcats.effect.warnOnNonMainThreadDetected=false") ++
    (if (javaMajor >= 17) Seq("--enable-native-access=ALL-UNNAMED") else Nil) ++
    (if (javaMajor >= 23) Seq("--sun-misc-unsafe-memory-access=allow") else Nil)

lazy val testDeps = Seq(
  "org.scalameta" %% "munit"             % V.munit           % Test,
  "org.typelevel" %% "munit-cats-effect" % V.munitCatsEffect % Test
)

lazy val root = (project in file("."))
  .aggregate(domain, application, adapterLocal, adapterLlmOpenAi, adapterLlmAnthropic, adapterSqlite, adapterHttp, adapterLangChain4j, adapterRemoteEngine, bootstrap, cli)
  .settings(name := "mahilo-scala", publish / skip := true)

// ---------- Lõi: domain thuần ----------
lazy val domain = (project in file("modules/domain"))
  .settings(name := "mahilo-domain", libraryDependencies ++= testDeps)

// ---------- Lõi: application (use case + port) ----------
lazy val application = (project in file("modules/application"))
  .dependsOn(domain)
  .settings(
    name := "mahilo-application",
    libraryDependencies ++= Seq("org.typelevel" %% "cats-effect" % V.catsEffect) ++ testDeps
  )

// ---------- Adapter không cần hệ thống ngoài: in-memory, LLM giả lập, HMAC ----------
lazy val adapterLocal = (project in file("modules/adapter-local"))
  .dependsOn(application)
  .settings(name := "mahilo-adapter-local", libraryDependencies ++= testDeps)

// ---------- Adapter LLM: API tương thích OpenAI Chat Completions ----------
lazy val adapterLlmOpenAi = (project in file("modules/adapter-llm-openai"))
  .dependsOn(application)
  .settings(
    name := "mahilo-adapter-llm-openai",
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core"   % V.circe,
      "io.circe" %% "circe-parser" % V.circe
    ) ++ testDeps
  )

// ---------- Adapter LLM: Claude qua SDK Java chính thức của Anthropic ----------
lazy val adapterLlmAnthropic = (project in file("modules/adapter-llm-anthropic"))
  .dependsOn(application)
  .settings(
    name := "mahilo-adapter-llm-anthropic",
    libraryDependencies ++= Seq("com.anthropic" % "anthropic-java" % V.anthropic) ++ testDeps
  )

// ---------- Adapter lưu trữ: SQLite qua JDBC ----------
lazy val adapterSqlite = (project in file("modules/adapter-persistence-sqlite"))
  .dependsOn(application)
  .settings(
    name := "mahilo-adapter-persistence-sqlite",
    libraryDependencies ++= Seq(
      "org.xerial" % "sqlite-jdbc"  % V.sqlite,
      "io.circe"  %% "circe-core"   % V.circe,
      "io.circe"  %% "circe-parser" % V.circe
    ) ++ testDeps
  )

// ---------- Adapter LangChain4j + LangGraph4j ----------
// Bản Python có `integrations/langgraph` (bọc một StateGraph của LangGraph thành agent
// Mahilo). Bản Scala giữ khả năng đó bằng LangGraph4j + LangChain4j, đặt hết trong
// MỘT adapter để lõi không biết gì về hai thư viện này.
lazy val adapterLangChain4j = (project in file("modules/adapter-langchain4j"))
  .dependsOn(application)
  .settings(
    name := "mahilo-adapter-langchain4j",
    libraryDependencies ++= Seq(
      "dev.langchain4j"   % "langchain4j-core"      % V.langchain4j,
      "dev.langchain4j"   % "langchain4j-open-ai"   % V.langchain4j,
      "org.bsc.langgraph4j" % "langgraph4j-core"    % V.langgraph4j,
      // Serializer cho state chứa ChatMessage của LangChain4j (LangGraph4j clone state giữa các node)
      ("org.bsc.langgraph4j" % "langgraph4j-langchain4j" % V.langgraph4j)
        .exclude("dev.langchain4j", "langchain4j-skills")
    ) ++ testDeps
  )

// ---------- Adapter engine ngoài tiến trình (vd service Python PydanticAI) ----------
// Hợp đồng REST: docs/remote-engine-contract.md. Service Python: engines/pydanticai-engine.
lazy val adapterRemoteEngine = (project in file("modules/adapter-remote-engine"))
  .dependsOn(application)
  .settings(
    name := "mahilo-adapter-remote-engine",
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-dsl"   % V.http4s,
      "org.http4s" %% "http4s-circe" % V.http4s,
      "io.circe"   %% "circe-parser" % V.circe
    ) ++ testDeps
  )

// ---------- Adapter vào (driving): HTTP + WebSocket bằng http4s ----------
lazy val adapterHttp = (project in file("modules/adapter-http"))
  .dependsOn(application)
  .settings(
    name := "mahilo-adapter-http",
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-server" % V.http4s,
      "org.http4s" %% "http4s-dsl"          % V.http4s,
      "org.http4s" %% "http4s-circe"        % V.http4s,
      "io.circe"   %% "circe-core"          % V.circe
    ) ++ testDeps
  )

// ---------- Composition root: lắp ráp + ví dụ + main ----------
lazy val bootstrap = (project in file("modules/bootstrap"))
  .dependsOn(adapterLocal, adapterLlmOpenAi, adapterLlmAnthropic, adapterSqlite, adapterHttp, adapterLangChain4j, adapterRemoteEngine)
  .settings(
    name := "mahilo-bootstrap",
    libraryDependencies ++= Seq(
      "ch.qos.logback" % "logback-classic"     % V.logback,
      "org.http4s"    %% "http4s-ember-client" % V.http4s % Test // engine giả trong RemoteEngineSpec
    ) ++ testDeps,
    run / fork := true,
    run / connectInput := true,
    // In thẳng ra terminal: sbt không đọc lại output (đọc lại thì trên Windows hỏng font tiếng Việt
    // và mọi dòng stderr bị gắn nhãn [error]).
    run / outputStrategy := Some(StdoutOutput),
    run / javaOptions ++= runJvmOptions,
    // Chạy từ thư mục gốc repo, để `.env` và `mahilo.db` nằm ở đó (mặc định sbt chạy trong modules/bootstrap).
    run / baseDirectory := (ThisBuild / baseDirectory).value,
    Compile / mainClass := Some("mahilo.bootstrap.DemoMain")
  )

// ---------- Client dòng lệnh (giống `mahilo connect`) ----------
// Chương trình riêng, không phụ thuộc lõi: chỉ nói chuyện qua WebSocket /ws/{agent}.
lazy val cli = (project in file("modules/cli"))
  .settings(
    name := "mahilo-cli",
    libraryDependencies ++= testDeps,
    run / fork := true,
    run / connectInput := true,
    run / outputStrategy := Some(StdoutOutput),
    run / javaOptions ++= runJvmOptions,
    Compile / mainClass := Some("mahilo.cli.MahiloCli")
  )
