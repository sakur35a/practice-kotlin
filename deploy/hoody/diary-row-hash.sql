-- 행 단위 해시 목록 (id 순서). 데이터 이전 검증과 백업 복원 검증이 같이 쓴다.
-- MySQL 쪽(deploy/migrate/diary-export-heatwave)이 같은 정규 문자열의 sha2(...,256)을 만든다:
--   id(32자리 소문자 16진수) | UTC 시각(마이크로초) | hex(title UTF-8) | hex(content UTF-8)
-- 16진수로 바꾸는 이유: 줄바꿈, 탭, 따옴표, 이모지 같은 어떤 글자도 정규 문자열 안에서 구분자와 섞이지 않는다.
select replace(id::text, '-', '') || E'\t' ||
       encode(sha256(convert_to(
           replace(id::text, '-', '') || '|' ||
           to_char(created_at at time zone 'UTC', 'YYYY-MM-DD HH24:MI:SS.US') || '|' ||
           encode(convert_to(title, 'UTF8'), 'hex') || '|' ||
           encode(convert_to(content, 'UTF8'), 'hex'),
           'UTF8')), 'hex')
from diaries
order by 1;
