"""ADAPTER VÀO: FastAPI.

Chạy:
    PYDANTICAI_MODEL=demo uvicorn mahilo_pydanticai.app:app --port 8001
    PYDANTICAI_MODEL=openai:gpt-4o-mini OPENAI_API_KEY=... uvicorn mahilo_pydanticai.app:app --port 8001
"""

import os
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI

from .contract import TurnRequest, TurnResponse
from .demo_model import demo_model
from .engine import run_turn
from .mahilo_client import HttpToolCaller


def _model_for(agent_name: str):
    """``demo`` = model giả lập theo vai agent; còn lại là tên model PydanticAI, vd ``openai:gpt-4o-mini``."""
    name = os.getenv("PYDANTICAI_MODEL", "demo")
    return demo_model(agent_name) if name == "demo" else name


@asynccontextmanager
async def lifespan(app: FastAPI):
    async with httpx.AsyncClient() as client:
        app.state.http = client
        yield


app = FastAPI(title="Mahilo PydanticAI engine", lifespan=lifespan)


@app.get("/health")
async def health() -> dict:
    return {"status": "ok", "model": os.getenv("PYDANTICAI_MODEL", "demo")}


@app.post("/v1/turns", response_model=TurnResponse)
async def turns(req: TurnRequest) -> TurnResponse:
    caller = HttpToolCaller(req.callback.url, app.state.http)
    return await run_turn(req, _model_for(req.agent.name), caller)
