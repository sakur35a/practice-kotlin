-- PostgreSQL V1~V4를 거친 최종 스키마를 MySQL(HeatWave)로 옮긴 것
-- id: UUID v7. binary(16)은 바이트 순서가 곧 시간 순서라 id 기준 keyset 정렬이 유지된다
-- created_at: 항상 UTC
create table diaries (
    id         binary(16)   not null primary key,
    created_at datetime(6)  not null,
    title      varchar(255) not null,
    content    text         not null
);
