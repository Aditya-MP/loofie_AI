from sqlalchemy import func
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.orm import Session

CHUNK = 500


def upsert(
    session: Session, model, rows: list[dict], key: list[str], prefer: str = "new", overwrite: tuple[str, ...] = ()
) -> None:
    """Insert rows, or update the existing row with the same key.

    A blank cell never erases a stored value. prefer="new" lets non-blank new values win;
    prefer="existing" only fills columns that are still empty (used for older, weaker sources).
    Columns in `overwrite` always take a non-blank new value, whatever `prefer` says.
    """
    table = model.__table__
    for i in range(0, len(rows), CHUNK):
        chunk = rows[i : i + CHUNK]
        stmt = insert(model).values(chunk)
        cols = [c for c in chunk[0] if c not in key]
        update = {
            c: func.coalesce(stmt.excluded[c], table.c[c])
            if prefer == "new" or c in overwrite
            else func.coalesce(table.c[c], stmt.excluded[c])
            for c in cols
        }
        if "updated_at" in table.c:
            update["updated_at"] = func.now()
        session.execute(stmt.on_conflict_do_update(index_elements=key, set_=update))
