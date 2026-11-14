package mahilo.adapters.http

import org.http4s.dsl.io.*

/** Tham số query `recipient` của [[MahiloRoutes]]. */
private[http] object RecipientQ extends OptionalQueryParamDecoderMatcher[String]("recipient")
