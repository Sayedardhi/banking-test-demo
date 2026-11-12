-- Fictional payees with predictable labels for the interview exercise.
UPDATE users SET firstname = 'Jordan', lastname = 'Lee' WHERE username = 'testuser';
UPDATE contacts SET label = 'Northstar Coffee' WHERE username = 'testuser' AND account_num = '1033623433';
UPDATE contacts SET label = 'Harbor Market' WHERE username = 'testuser' AND account_num = '1055757655';
UPDATE contacts SET label = 'Metro Utilities' WHERE username = 'testuser' AND account_num = '1077441377';
UPDATE contacts SET label = 'Monthly Salary' WHERE username = 'testuser' AND is_external;
INSERT INTO contacts (username, label, account_num, routing_num, is_external) VALUES
('testuser', 'Studio, North', '2000000001', '883745000', false),
('testuser', '=SUM(1,1)', '2000000002', '883745000', false);
