"""Time keys shared by authoring checks and the package compiler."""


def chronological(point, *, end_of_period=False):
    """Order a dated point on the Hijri axis, optionally at the end of its precision."""
    year = -point["year"] if point["calendar"] == "pre-hijra" else point["year"]
    return (
        year,
        point.get("month", 12 if end_of_period else 0),
        point.get("day", 31 if end_of_period else 0),
    )
