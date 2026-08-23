import asyncio
from mahilo.client import Client


async def main():
    client = Client(
        url="http://localhost:8000",
        agent_name="SalesAgent"
    )

    await client.connect()

    while True:
        message = input("You: ")

        if message.lower() in ("exit", "quit"):
            break

        await client.send_message(message)

    await client.close()


if __name__ == "__main__":
    asyncio.run(main())