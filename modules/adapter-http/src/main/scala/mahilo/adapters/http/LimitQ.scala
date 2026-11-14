package mahilo.adapters.http

import org.http4s.dsl.io.*

/** Tham số query `limit` của [[MahiloRoutes]]. */
private[http] object LimitQ extends OptionalQueryParamDecoderMatcher[Int]("limit")
