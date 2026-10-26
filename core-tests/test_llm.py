"""Tests for the provider client.

No network. `urlopen` is replaced, because the behaviour under test is
how this code treats a provider's answer - including the wrong answers,
which is where the owner-visible failures come from.
"""

from __future__ import annotations

import io
import json
import urllib.error
import urllib.request

import pytest

from harness_core.compat import Dialect, normalize_base_url
from harness_core.llm import (
    OpenAICompatiblePlanner,
    PlannerAuthError,
    PlannerModelError,
    PlannerProtocolError,
    PlannerTransportError,
    list_models,
)

GEMINI = "https://generativelanguage.googleapis.com"
LOCAL = "http://127.0.0.1:11434/v1"


class FakeResponse(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()
        return False


def respond_with(monkeypatch, payload, *, capture=None):
    body = payload if isinstance(payload, bytes) else json.dumps(payload).encode()

    def fake_urlopen(request, timeout=None):
        if capture is not None:
            capture["url"] = request.full_url
            capture["headers"] = dict(request.header_items())
            capture["method"] = request.get_method()
        return FakeResponse(body)

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)


def fail_with(monkeypatch, status, detail="nope"):
    def fake_urlopen(request, timeout=None):
        raise urllib.error.HTTPError(
            request.full_url, status, "error", {}, io.BytesIO(detail.encode())
        )

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)


class TestFetchingTheModelList:
    def test_it_returns_the_names(self, monkeypatch):
        respond_with(monkeypatch, {"data": [{"id": "gpt-4o"}, {"id": "gpt-4o-mini"}]})
        assert list_models("https://api.openai.com/v1", "k") == ["gpt-4o", "gpt-4o-mini"]

    def test_gemini_names_are_returned_without_their_prefix(self, monkeypatch):
        # Gemini lists "models/gemini-2.5-flash" but its chat endpoint
        # wants "gemini-2.5-flash". Offering the listed form would hand
        # the owner a name that does not work.
        respond_with(monkeypatch, {"data": [{"id": "models/gemini-2.5-flash"}]})
        assert list_models(GEMINI, "k") == ["gemini-2.5-flash"]

    def test_duplicates_collapse_and_order_is_stable(self, monkeypatch):
        respond_with(
            monkeypatch,
            {"data": [{"id": "models/x"}, {"id": "x"}, {"id": "a"}]},
        )
        assert list_models(LOCAL) == ["a", "x"]

    def test_it_asks_the_right_url(self, monkeypatch):
        captured = {}
        respond_with(monkeypatch, {"data": []}, capture=captured)
        list_models(GEMINI, "secret")
        assert captured["url"] == f"{GEMINI}/v1beta/openai/models"
        assert captured["method"] == "GET"

    def test_the_key_travels_in_a_header_only(self, monkeypatch):
        captured = {}
        respond_with(monkeypatch, {"data": []}, capture=captured)
        list_models(LOCAL, "secret-key")
        assert captured["headers"]["Authorization"] == "Bearer secret-key"
        assert "secret-key" not in captured["url"]

    def test_a_local_server_needs_no_key(self, monkeypatch):
        captured = {}
        respond_with(monkeypatch, {"data": [{"id": "llama3"}]}, capture=captured)
        assert list_models(LOCAL) == ["llama3"]
        assert "Authorization" not in captured["headers"]

    def test_a_bad_key_says_so(self, monkeypatch):
        fail_with(monkeypatch, 401)
        with pytest.raises(PlannerAuthError):
            list_models(LOCAL, "wrong")

    def test_a_wrong_path_says_so(self, monkeypatch):
        fail_with(monkeypatch, 404)
        with pytest.raises(PlannerModelError):
            list_models("https://example.com/wrong")

    def test_an_unreachable_host_says_so(self, monkeypatch):
        def refuse(request, timeout=None):
            raise urllib.error.URLError("connection refused")

        monkeypatch.setattr(urllib.request, "urlopen", refuse)
        with pytest.raises(PlannerTransportError):
            list_models(LOCAL)

    def test_an_html_page_is_not_mistaken_for_a_model_list(self, monkeypatch):
        # Pointing at a website instead of an API endpoint is a common
        # mistake and must not surface as a JSON traceback.
        respond_with(monkeypatch, b"<html>not an api</html>")
        with pytest.raises(PlannerProtocolError):
            list_models("https://example.com")

    def test_a_json_response_in_the_wrong_shape_is_refused(self, monkeypatch):
        respond_with(monkeypatch, {"models": ["a", "b"]})
        with pytest.raises(PlannerProtocolError) as error:
            list_models(LOCAL)
        assert "OpenAI-compatible" in str(error.value)


class TestTheBaseUrl:
    @pytest.mark.parametrize(
        "given",
        [
            GEMINI,
            f"{GEMINI}/",
            f"{GEMINI}/v1",
            f"{GEMINI}/v1beta",
            f"{GEMINI}/v1beta/openai",
        ],
    )
    def test_every_way_of_writing_the_gemini_url_ends_up_right(self, given):
        # A wrong path here returns an opaque 404, so the repair is worth
        # more than the purity of rejecting it.
        assert normalize_base_url(given, Dialect.GEMINI) == f"{GEMINI}/v1beta/openai"

    def test_other_providers_are_left_exactly_as_given(self):
        assert normalize_base_url(LOCAL, Dialect.OPENAI) == LOCAL
        assert normalize_base_url(f"{LOCAL}/", Dialect.OPENAI) == LOCAL

    def test_the_dialect_is_guessed_from_the_host(self):
        assert Dialect.detect(GEMINI) is Dialect.GEMINI
        assert Dialect.detect(LOCAL) is Dialect.OPENAI
        assert Dialect.detect("https://api.openai.com/v1") is Dialect.OPENAI


class TestTalkingToTheModel:
    def test_a_plain_answer_comes_back(self, monkeypatch):
        respond_with(
            monkeypatch,
            {"choices": [{"message": {"content": "hello"}, "finish_reason": "stop"}]},
        )
        planner = OpenAICompatiblePlanner(base_url=LOCAL, model="llama3")
        assert planner.complete([{"role": "user", "content": "hi"}], []).text == "hello"

    def test_a_tool_call_is_parsed(self, monkeypatch):
        respond_with(
            monkeypatch,
            {
                "choices": [
                    {
                        "message": {
                            "content": None,
                            "tool_calls": [
                                {
                                    "id": "c1",
                                    "function": {
                                        "name": "read_battery",
                                        "arguments": '{"text":"x"}',
                                    },
                                }
                            ],
                        }
                    }
                ]
            },
        )
        planner = OpenAICompatiblePlanner(base_url=LOCAL, model="llama3")
        call = planner.complete([], []).tool_calls[0]
        assert (call.name, dict(call.arguments)) == ("read_battery", {"text": "x"})

    def test_a_gemini_thought_signature_is_kept_verbatim(self, monkeypatch):
        # It must be replayed on the next turn or Gemini rejects it with
        # a 400. It is never interpreted and authorises nothing.
        respond_with(
            monkeypatch,
            {
                "choices": [
                    {
                        "message": {
                            "tool_calls": [
                                {
                                    "id": "c1",
                                    "function": {"name": "t", "arguments": "{}"},
                                    "extra_content": {"thought_signature": "abc123"},
                                }
                            ]
                        }
                    }
                ]
            },
        )
        planner = OpenAICompatiblePlanner(base_url=GEMINI, model="gemini-2.5-flash")
        call = planner.complete([], []).tool_calls[0]
        assert call.extra_content["thought_signature"] == "abc123"

    def test_content_returned_in_parts_is_joined(self, monkeypatch):
        respond_with(
            monkeypatch,
            {"choices": [{"message": {"content": [{"type": "text", "text": "a"},
                                                   {"type": "text", "text": "b"}]}}]},
        )
        planner = OpenAICompatiblePlanner(base_url=LOCAL, model="m")
        assert planner.complete([], []).text == "ab"

    def test_an_envelope_with_no_choices_is_refused(self, monkeypatch):
        respond_with(monkeypatch, {"id": "x"})
        planner = OpenAICompatiblePlanner(base_url=LOCAL, model="m")
        with pytest.raises(PlannerProtocolError):
            planner.complete([], [])

    def test_the_api_key_never_appears_in_an_error(self, monkeypatch):
        fail_with(monkeypatch, 401, detail="invalid key")
        planner = OpenAICompatiblePlanner(base_url=LOCAL, model="m", api_key="sk-secret")
        with pytest.raises(PlannerAuthError) as error:
            planner.complete([], [])
        assert "sk-secret" not in str(error.value)
