package mahilo.adapters.http

import org.http4s.dsl.io.*

/** Tham số query `agent_id` của [[MahiloRoutes]]. */
private[http] object AgentQ extends OptionalQueryParamDecoderMatcher[String]("agent_id")
