from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

import app.models

from app.api.auth import router as auth_router
from app.api.user import router as user_router
from app.api.contact import router as contact_router
from app.api.email import router as email_router
from app.api.campaign import router as campaign_router
from app.api import tracking, analytics
from app.api.ai import router as ai_router


app = FastAPI(
    title="AI Outreach Automation System",
    version="1.0.0",
    description="AI-powered outreach automation backend"
)


# ---------------------------------------------------------
# CORS
# ---------------------------------------------------------
# Development configuration for now.
# Before production deployment, replace "*" with the
# actual frontend URL(s).
# ---------------------------------------------------------

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ---------------------------------------------------------
# API Routers
# ---------------------------------------------------------

app.include_router(auth_router)
app.include_router(user_router)
app.include_router(contact_router)
app.include_router(email_router)
app.include_router(campaign_router)
app.include_router(tracking.router)
app.include_router(analytics.router)
app.include_router(ai_router)


# ---------------------------------------------------------
# Root / Health
# ---------------------------------------------------------

@app.get("/")
def root():
    return {
        "message": "Backend running 🚀"
    }


@app.get("/health")
def health_check():
    return {
        "status": "healthy"
    }