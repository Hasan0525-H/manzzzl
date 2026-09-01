# User Project Schema

## User
- id
- email (future)
- phone (future)
- storage_quota

## Home Project
- id
- user_id
- name
- city
- floors: 1 | 2
- status

## Files
- original_plan
- generated_3d
- rendered_images

## Processing states
1. uploaded
2. analyzing
3. questions_required
4. validated
5. generating_3d
6. rendered
7. completed
