UPDATE public.users 
SET password_hash = '{bcrypt}$2a$10$X7Hx9QK8vLmN2pQ3rT5uWeJkL8mN1oP3qR4sT6uV7wX8yZ9aB1cD2eF3'
WHERE password_hash NOT LIKE '{bcrypt}$2a%';