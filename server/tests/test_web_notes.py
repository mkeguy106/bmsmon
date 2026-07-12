USER = {"X-authentik-username": "joel",
        "X-authentik-groups": "Covert.life - Full App Access - User Group"}


async def test_notes_round_trip_and_upsert(client):
    assert (await client.get("/web/notes", headers=USER)).json() == {"notes": []}
    r = await client.post("/web/notes", headers=USER, json={"base_id": "2012", "body": "swapped B in 2023"})
    assert r.status_code == 200
    got = (await client.get("/web/notes", headers=USER)).json()["notes"]
    assert got == [{"base_id": "2012", "body": "swapped B in 2023", "updated_at_ms": got[0]["updated_at_ms"]}]
    await client.post("/web/notes", headers=USER, json={"base_id": "2012", "body": "updated"})
    got2 = (await client.get("/web/notes", headers=USER)).json()["notes"]
    assert len(got2) == 1 and got2[0]["body"] == "updated"


async def test_notes_requires_identity(client):
    assert (await client.get("/web/notes")).status_code == 401


async def test_notes_over_length_rejected(client):
    r = await client.post("/web/notes", headers=USER, json={"base_id": "2012", "body": "x" * 5000})
    assert r.status_code == 422


async def test_notes_bad_base_id_rejected(client):
    assert (await client.post("/web/notes", headers=USER,
            json={"base_id": "", "body": "x"})).status_code == 422
    assert (await client.post("/web/notes", headers=USER,
            json={"base_id": "z" * 65, "body": "x"})).status_code == 422
