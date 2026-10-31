-- 本地共享实例上的两个虚构租户库。生产由开通编排建库，不走这个脚本。
CREATE DATABASE IF NOT EXISTS t_demoa CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS t_demob CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL PRIVILEGES ON t_demoa.* TO 'fu_app'@'%';
GRANT ALL PRIVILEGES ON t_demob.* TO 'fu_app'@'%';
FLUSH PRIVILEGES;
