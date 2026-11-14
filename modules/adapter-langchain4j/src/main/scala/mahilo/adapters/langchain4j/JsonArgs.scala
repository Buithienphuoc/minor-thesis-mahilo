package mahilo.adapters.langchain4j

import scala.jdk.CollectionConverters.*

/** Đọc JSON tham số tool thành Map bằng Jackson (LangChain4j đã kéo theo Jackson). */
object JsonArgs:
  private val mapper = com.fasterxml.jackson.databind.ObjectMapper()

  def parse(raw: String): Map[String, String] =
    try
      val node = mapper.readTree(raw)
      if node == null || !node.isObject then Map.empty
      else node.properties().asScala.map(e => e.getKey -> (if e.getValue.isTextual then e.getValue.asText() else e.getValue.toString)).toMap
    catch case _: Exception => Map.empty
