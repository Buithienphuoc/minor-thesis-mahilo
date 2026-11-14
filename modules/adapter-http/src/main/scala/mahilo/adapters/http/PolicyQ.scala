package mahilo.adapters.http

import org.http4s.dsl.io.*

/** Tham số query `policy_name` của [[MahiloRoutes]]. */
private[http] object PolicyQ extends OptionalQueryParamDecoderMatcher[String]("policy_name")
