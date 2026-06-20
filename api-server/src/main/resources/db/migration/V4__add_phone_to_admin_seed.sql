-- Add phone to seeded admin user for dev/test purposes
UPDATE users SET phone = '+84900000000' WHERE email = 'admin@fams.com';
