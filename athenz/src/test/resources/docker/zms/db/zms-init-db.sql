-- Create the 'zms_admin' user and grant it privileges on the 'zms_server' database.
CREATE USER 'zms_admin'@'%' IDENTIFIED BY 'mariadbmariadb';
GRANT ALL PRIVILEGES ON zms_server.* TO 'zms_admin'@'%';

-- Apply the changes.
FLUSH PRIVILEGES;
