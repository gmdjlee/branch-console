"""Schema-only checks for configs/*.yaml (SSOT). No thresholds/values duplicated here.

Network-free: fixtures are the config files themselves.
"""

from __future__ import annotations

from pathlib import Path

import yaml

CONFIGS_DIR = Path(__file__).resolve().parent.parent / "configs"


def _load(name: str) -> dict:
    # K-xx Windows 함정: cp949 기본 인코딩 회피 — 반드시 utf-8 명시.
    with open(CONFIGS_DIR / name, encoding="utf-8") as f:
        return yaml.safe_load(f)


def test_all_configs_parse() -> None:
    yaml_files = sorted(CONFIGS_DIR.glob("*.yaml"))
    assert len(yaml_files) == 5, (
        f"expected 5 config files, found {[p.name for p in yaml_files]}"
    )
    for path in yaml_files:
        with open(path, encoding="utf-8") as f:
            assert yaml.safe_load(f) is not None


def test_statemachine_profiles_structure() -> None:
    d = _load("statemachine.yaml")
    profiles = d["profiles"]
    required_keys = {
        "tick",
        "promote_sustain_ticks",
        "demote_below_ticks",
        "min_dwell_ticks",
    }
    for name in ("server_intraday", "mobile_daily"):
        assert name in profiles
        assert required_keys.issubset(profiles[name].keys())


def test_indicators_stale_profiles_structure() -> None:
    d = _load("indicators.yaml")
    stale_profiles = d["engine"]["stale_profiles"]
    assert "server_intraday" in stale_profiles
    assert "mobile_daily" in stale_profiles


def test_sources_new_providers_present() -> None:
    d = _load("sources.yaml")
    providers = d["providers"]
    assert "kis" in providers
    assert "stooq" in providers


def test_mt0_08_variant_a_adoption_reflected_in_ssot() -> None:
    """MT0-08(2026-08-04, GATE_GM0 후속 결정) — ① 변형(kospi_drawdown extreme:20.0% +
    upgrade.ORANGE.or_any_extreme) 채택 값이 SSOT에 실제로 반영됐는지 가드. D-26 짝지음
    자체는 엔진 의미론(engine_ref/statemachine.py)이라 이 파일에 별도 키가 없다."""
    ind = _load("indicators.yaml")
    assert ind["registry_version"] == "0.3.1-rc"
    kospi = next(i for i in ind["indicators"] if i["id"] == "kospi_drawdown")
    assert kospi["thresholds"]["extreme"] == 20.0

    sm = _load("statemachine.yaml")
    assert sm["upgrade"]["rules"]["ORANGE"]["or_any_extreme"] is True
    assert "or_any_extreme" not in sm["upgrade"]["rules"]["RED"]  # AD-10: RED는 대상 아님
    assert "or_any_extreme" not in sm["upgrade"]["rules"]["AMBER"]


def test_contracts_importable() -> None:
    import contracts.evidence
    import contracts.snapshot

    assert contracts.evidence.MarketSnapshot
    assert contracts.snapshot.Phase
