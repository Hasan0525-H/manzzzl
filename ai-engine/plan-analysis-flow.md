# Plan Analysis Flow

1. Receive uploaded plan.
2. Detect walls, rooms, doors, windows and floors.
3. Assign confidence score to each detected element.
4. Ask user only about uncertain items.
5. Validate corrected plan.
6. Prepare 3D generation input.

Rules:
- Never invent rooms.
- Never change dimensions.
- Saudi architecture affects style, not structure.
