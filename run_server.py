from examples.team_of_agents.control_plane import marketing_agent_prompt, sales_agent_prompt, sales_tools, graph_builder
from mahilo import BaseAgent, AgentManager, ServerManager
from mahilo.integrations.langgraph.agent import LangGraphAgent

# a mahilo agent
sales_agent = BaseAgent(
    type="SalesAgent",
    name="SalesAgent",
    description=sales_agent_prompt,
    tools=sales_tools,
)

# a langgraph agent
marketing_agent = LangGraphAgent(
    langgraph_agent=graph_builder,
    name="MarketingAgent",
    description=marketing_agent_prompt,
    can_contact=[],
)

# Create Agent Manager (think of it as a team)
manager = AgentManager()
manager.register_agent(sales_agent)
manager.register_agent(marketing_agent)

# activate any agents with runtime params (here server_id is the thread_id for the langgraph agent)
sales_agent.activate(server_id="1")
marketing_agent.activate(server_id="1")

# initialize the server manager
server = ServerManager(manager)
# Start WebSocket Server
print("Registered agents:")
print(manager.agents)

print("SalesAgent:", sales_agent)
print("MarketingAgent:", marketing_agent)

server.run()