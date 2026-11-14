package mahilo.cli

class MahiloCliSpec extends munit.FunSuite:
  test("parse giống `mahilo connect --agent-name X --url ...`") {
    assertEquals(MahiloCli.parse(List("connect", "--agent-name", "SalesAgent")), Right(CliOptions("SalesAgent")))
    assertEquals(
      MahiloCli.parse(List("-a", "plumber", "--url", "http://10.0.0.5:9000", "--json")),
      Right(CliOptions("plumber", "http://10.0.0.5:9000", json = true))
    )
    assert(MahiloCli.parse(Nil).isLeft)
    assert(MahiloCli.parse(List("--bogus")).isLeft)
  }

  test("URL WebSocket và chế độ --json giống client Python") {
    assertEquals(MahiloCli.wsUri(CliOptions("SalesAgent")).toString, "ws://localhost:8000/ws/SalesAgent")
    assertEquals(MahiloCli.wsUri(CliOptions("x", "https://mahilo.example.com/")).toString, "wss://mahilo.example.com/ws/x")
    // json.dumps("Tôi") == "\"T\\u00f4i\""
    assertEquals(MahiloCli.encode(CliOptions("x", json = true), "Tôi \"ok\""), "\"T\\u00f4i \\\"ok\\\"\"")
    assertEquals(MahiloCli.encode(CliOptions("x"), "Tôi"), "Tôi")
  }
