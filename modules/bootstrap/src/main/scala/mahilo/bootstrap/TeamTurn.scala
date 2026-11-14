package mahilo.bootstrap

/** Những gì [[TeamBrain]] cần biết về một lượt: agent nào, tin từ hàng đợi hay từ người dùng,
  * ai gửi, nội dung, câu người dùng gõ, và tool vừa chạy xong (tên, kết quả).
  */
final case class TeamTurn(agent: String, fromQueue: Boolean, sender: String, payload: String, human: String, lastTool: Option[(String, String)])
