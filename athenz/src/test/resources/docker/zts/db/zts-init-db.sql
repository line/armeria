-- Create the 'zts_store' database if it doesn't exist.
CREATE DATABASE IF NOT EXISTS zts_store;

-- Create the 'zts_admin' user and grant it privileges on the zts_store database.
CREATE USER 'zts_admin'@'%' IDENTIFIED BY 'mariadbmariadb';
GRANT ALL PRIVILEGES ON zts_store.* TO 'zts_admin'@'%';

-- Apply the changes.
FLUSH PRIVILEGES;
