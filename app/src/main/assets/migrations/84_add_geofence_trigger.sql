-- 位置围栏触发：任务表补围栏中心、半径与进出方向。
-- 坐标可空：未设置坐标的任务不会被注册进系统围栏，等同于「配了 location 触发但还没选地点」。
ALTER TABLE scheduled_tasks ADD COLUMN triggerLatitude REAL;
ALTER TABLE scheduled_tasks ADD COLUMN triggerLongitude REAL;
ALTER TABLE scheduled_tasks ADD COLUMN triggerRadiusMeters INTEGER NOT NULL DEFAULT 200;
ALTER TABLE scheduled_tasks ADD COLUMN triggerOnEnter INTEGER NOT NULL DEFAULT 1;
ALTER TABLE scheduled_tasks ADD COLUMN triggerOnExit INTEGER NOT NULL DEFAULT 1;
