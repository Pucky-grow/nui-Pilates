-- Run manually ONLY after the resource/account/region and isolation have been confirmed.
-- A name collision fails instead of silently reusing an unknown database.
CREATE DATABASE `uni_pilates_test`
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
