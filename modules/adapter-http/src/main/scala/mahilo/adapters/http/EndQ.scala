package mahilo.adapters.http

import org.http4s.dsl.io.*

/** Tham số query `end_time` của [[MahiloRoutes]]. */
private[http] object EndQ extends OptionalQueryParamDecoderMatcher[Double]("end_time")
