from life_agent_backend.app import create_app
from life_agent_backend.settings import Settings

app = create_app(Settings.from_environment())
