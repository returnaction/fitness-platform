-- Стартовый каталог, утверждённый в D001. Данные добавляются только этим changeset.
INSERT INTO catalog_categories (id, code, name, is_active, sort_order, version)
VALUES (1, 'BODYWEIGHT', 'Bodyweight Exercises', true, 10, 1),
       (2, 'CARDIO', 'Cardio', true, 20, 1);

INSERT INTO catalog_exercises(id, category_id, code, name, description, primary_metric, is_active, sort_order, version)
VALUES (1, 1, 'SQUAT', 'Squats', 'Bodyweight squats.', 'REPETITIONS', true, 10, 1),
       (2, 1, 'PUSH_UP', 'Push-ups', 'Push-ups in the selected variation.', 'REPETITIONS', true, 20, 1),
       (3, 1, 'BURPEE', 'Burpees', 'Burpees in the selected variation.', 'REPETITIONS', true, 30, 1),
       (4, 1, 'JUMPING_JACK', 'Jumping Jacks', 'Jumping jacks with coordinated arm and leg movement.', 'REPETITIONS',true, 40, 1),
       (5, 2, 'JUMP_ROPE', 'Jump Rope', 'Jumping rope.', 'REPETITIONS', true, 10, 1),
       (6, 1, 'PLANK', 'Plank', 'Static plank hold.', 'DURATION', true, 50, 1);

INSERT INTO catalog_variations
(id, exercise_id, code, name, description, instructions, min_target, max_target, target_step, is_active, sort_order, version)
VALUES (1, 1, 'STANDARD', 'Standard', 'Standard squats.',
        'Stand with your feet approximately shoulder-width apart, squat in a controlled motion, then return to the starting position.',
        1, 200, 1, true, 10, 1),
       (2, 2, 'STANDARD', 'Standard', 'Standard push-ups.',
        'Place your palms and toes on the floor, keep your body straight, lower yourself, then push back up.',
        1, 150, 1, true, 10, 1),
       (3, 2, 'ELEVATED_FEET', 'Elevated Feet', 'Push-ups with feet on a stable elevated surface.',
        'Place your feet on a stable elevated surface, keep your hands on the floor, and perform controlled push-ups with a straight body.',
        1, 100, 1, true, 20, 1),
       (4, 2, 'NARROW', 'Narrow Grip', 'Push-ups with a narrow hand position.',
        'Place your palms closer than shoulder-width apart and perform controlled push-ups with a straight body.',
        1, 100, 1, true, 30, 1),
       (5, 2, 'WIDE', 'Wide Grip', 'Push-ups with a wide hand position.',
        'Place your palms wider than shoulder-width apart and perform controlled push-ups with a straight body.',
        1, 100, 1, true, 40, 1),
       (6, 2, 'KNEE', 'Knee Push-ups', 'Push-ups supported on the knees.',
        'Place your palms and knees on the floor, keep your body straight, and perform controlled push-ups.',
        1, 150, 1, true, 50, 1),
       (7, 3, 'STANDARD', 'Standard', 'Standard burpees.',
        'Move from standing to a plank position, return to standing, and complete the repetition with a controlled rise.',
        1, 100, 1, true, 10, 1),
       (8, 3, 'JUMP', 'With Jump', 'Burpees with a vertical jump.',
        'Perform a burpee and, after returning to standing, make a vertical jump.',
        1, 75, 1, true, 20, 1),
       (9, 3, 'RAISED_ARMS_JUMP', 'Jump with Raised Arms', 'Burpees with a jump and raised arms.',
        'Perform a burpee, then jump upward and raise your arms fully above your head.',
        1, 50, 1, true, 30, 1),
       (10, 4, 'STANDARD', 'Standard', 'Standard jumping jacks.',
        'Move your legs apart while raising your arms above your head, then return to the starting position.',
        1, 300, 1, true, 10, 1),
       (11, 5, 'STANDARD', 'Standard', 'Standard jump rope.',
        'Perform consecutive jumps over a rope at a safe, controlled pace.',
        10, 1000, 1, true, 10, 1),
       (12, 6, 'STANDARD', 'Forearm', 'Standard forearm plank.',
        'Support yourself on your forearms and toes, keep your body straight, and hold the position for the specified number of seconds.',
        10, 600, 1, true, 10, 1);
