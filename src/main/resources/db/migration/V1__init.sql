create table diary (
    id uuid primary key,
    created_at timestamp,
    title varchar(255),
    content text
)