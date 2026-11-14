package mahilo.adapters.http

import org.http4s.dsl.io.*

/** Tham số query `sender` của [[MahiloRoutes]]. */
private[http] object SenderQ extends OptionalQueryParamDecoderMatcher[String]("sender")
