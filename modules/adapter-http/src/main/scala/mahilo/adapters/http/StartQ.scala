package mahilo.adapters.http

import org.http4s.dsl.io.*

/** Tham số query `start_time` của [[MahiloRoutes]]. */
private[http] object StartQ extends OptionalQueryParamDecoderMatcher[Double]("start_time")
