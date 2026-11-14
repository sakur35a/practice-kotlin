-- 새 PostgreSQL 데이터베이스용 스키마 (빈 DB에서 시작한다. 기존 데이터는 deploy/migrate의 일회성 이전 스크립트가 옮긴다)
--
-- id: UUID v7. PostgreSQL uuid는 16바이트로 저장하고, 비교는 16바이트를 앞에서부터 부호 없이 비교(memcmp)한다.
--     UUID v7은 앞 48비트가 밀리초 시각이라 이 순서가 곧 시간 순서이고, id 기준 keyset 정렬(id < ? order by id desc)이
--     그대로 맞다. (자바 UUID.compareTo는 부호 있는 long 비교라 달라질 수 있지만, 정렬은 항상 DB가 한다)
--     varchar(36)이나 bytea(16)이 아니라 uuid를 쓰는 이유: 절반 이하의 크기, 인덱스가 작음, 형식이 틀린 값은 DB가 거부
-- created_at: timestamptz. 내부에는 UTC 순간으로 저장하고(마이크로초 정밀도), 앱은 Instant로 읽고 쓴다
-- title: varchar(255). 글자(코드 포인트) 수 기준이라 MySQL과 같다
-- content: text. 크기 제한이 없다(최대 1GB). MySQL 때처럼 mediumtext(16MB) 상한을 걱정하지 않는다
create table diaries (
    id         uuid                     not null primary key,
    created_at timestamp with time zone not null,
    title      varchar(255)             not null,
    content    text                     not null
);
