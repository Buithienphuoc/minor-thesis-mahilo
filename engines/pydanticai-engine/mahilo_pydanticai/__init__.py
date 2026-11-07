"""Engine PydanticAI cho Mahilo-Scala.

Cấu trúc (cũng theo kiểu lục giác, thu nhỏ):

- ``contract.py``     : kiểu dữ liệu của hợp đồng REST (không phụ thuộc gì ngoài pydantic)
- ``engine.py``       : LÕI. Chạy một lượt bằng PydanticAI. Chỉ biết port ``ToolCaller``.
- ``history.py``      : chuyển tin định dạng OpenAI ↔ message của PydanticAI
- ``mahilo_client.py``: ADAPTER cho ``ToolCaller``: gọi callback HTTP về Mahilo-Scala
- ``demo_model.py``   : model giả lập (FunctionModel) để chạy không cần API key
- ``app.py``          : ADAPTER VÀO: FastAPI, ``POST /v1/turns``
"""
