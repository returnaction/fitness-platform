-- История всех движений монет: кому, когда, сколько начислили, зарезервировали или списали и почему.
-- Каждая строка показывает изменение доступного и зарезервированного баланса пользователя.
-- Например: подарок 10 монет — available_delta = 10, locked_delta = 0;
-- резерв ставки 10 монет — available_delta = -10, locked_delta = 10.
-- Текущий баланс хранится в coin_wallets, а эта таблица объясняет, как он получился.
-- Изменение баланса и запись в историю должны сохраняться вместе, в одной транзакции.
-- Старые записи нельзя менять или удалять: ошибки исправляются новой записью.
CREATE TABLE coin_ledger_entries (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES coin_wallets (user_id) ON DELETE RESTRICT,
    -- ID общего действия: например, все записи расчёта одного соревнования имеют один operation_id.
    operation_id UUID NOT NULL,
    -- Номер записи внутри действия, начиная с 0. Для подарка с одной записью — 0.
    -- Пара (operation_id, entry_index) уникальна, чтобы повтор не добавил ту же запись ещё раз.
    entry_index INTEGER NOT NULL CHECK (entry_index >= 0),
    entry_type VARCHAR(32) NOT NULL CHECK (entry_type IN (
        'DAILY_REWARD_CREDIT',      -- Начисление ежедневного подарка в доступный баланс.
        'CHALLENGE_STAKE_LOCK',     -- Перевод ставки из доступного баланса в резерв соревнования.
        'CHALLENGE_STAKE_RELEASE',  -- Возврат своей ставки из резерва в доступный баланс.
        'CHALLENGE_STAKE_FORFEIT',  -- Списание проигранной ставки из резерва.
        'CHALLENGE_WIN_CREDIT',     -- Начисление выигрыша в доступный баланс, отдельно от возврата своей ставки.
        'ADMIN_ADJUSTMENT',         -- Корректировка баланса администратором с указанием причины.
        'ENTRY_REVERSAL'            -- Отмена ошибочной записи новым обратным движением монет; ссылка — reverses_entry_id.
    )),
    -- Изменение доступных монет, не итоговый баланс: подарок +10, резерв ставки -10.
    available_delta BIGINT NOT NULL,
    -- Изменение монет в резерве: резерв ставки +10, её возврат или списание -10.
    locked_delta BIGINT NOT NULL,
    challenge_id UUID NULL,
    -- ID ошибочной записи, которую отменяет эта новая запись. Саму старую запись не меняем.
    -- Например, отмена начисления +10 записывается как -10 со ссылкой на исходное начисление.
    -- Для обычного подарка, ставки или выигрыша — NULL.
    reverses_entry_id UUID NULL REFERENCES coin_ledger_entries (id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_coin_ledger_operation_entry UNIQUE (operation_id, entry_index),
    CONSTRAINT ck_coin_ledger_delta_not_zero CHECK (available_delta <> 0 OR locked_delta <> 0)
);
CREATE INDEX idx_coin_ledger_user_created
    ON coin_ledger_entries (user_id, created_at, id);

-- Эта функция всегда выдаёт ошибку: менять или удалять уже записанную историю монет нельзя.
-- Сама по себе она не следит за таблицей — её вызывает триггер ниже.
CREATE FUNCTION reject_coin_ledger_entry_mutation()
    RETURNS trigger
    LANGUAGE plpgsql
AS 'BEGIN RAISE EXCEPTION ''coin_ledger_entries is append-only''; END;';

-- Триггер автоматически вызывает функцию перед изменением (UPDATE) или удалением (DELETE)
-- каждой строки ledger. Ошибка из функции запрещает выполнить такое изменение или удаление.
-- Защита работает в самой БД, в том числе для прямого SQL-запроса, а не только через Java-код.
-- Добавлять новые записи (INSERT) можно. Ошибки в истории исправляем новой записью,
-- сохраняя исходную, чтобы было видно и первоначальную операцию, и её исправление.
CREATE TRIGGER coin_ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON coin_ledger_entries
    FOR EACH ROW
    EXECUTE FUNCTION reject_coin_ledger_entry_mutation();

-- Факт получения ежедневного подарка: кто, когда и сколько монет получил.
-- Каждое получение связано с одной записью начисления в ledger.
-- Эта таблица нужна для истории подарков и проверки доступности следующего.
-- Следующий подарок доступен через 24 часа после последнего claimed_at.
-- Интервал проверяет сервер под блокировкой кошелька в транзакции начисления:
-- сама таблица не запрещает два получения с интервалом меньше 24 часов.
CREATE TABLE daily_reward_claims (
    id UUID NOT NULL,
    user_id UUID NOT NULL,

    -- Точный момент получения: например, сегодня в 17:00 UTC.
    -- Следующий подарок разрешён не раньше завтра в 17:00 UTC, а не в полночь.
    claimed_at TIMESTAMPTZ NOT NULL,

    -- Фактически начисленная сумма подарка.
    reward_amount BIGINT NOT NULL,

    -- Запись ledger, описывающая начисление этого подарка.
    ledger_entry_id UUID NOT NULL,

    CONSTRAINT pk_daily_reward_claims
        PRIMARY KEY (id),

    CONSTRAINT fk_daily_reward_claims_wallet
        FOREIGN KEY (user_id)
        REFERENCES coin_wallets (user_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_daily_reward_claims_ledger_entry
        FOREIGN KEY (ledger_entry_id)
        REFERENCES coin_ledger_entries (id)
        ON DELETE RESTRICT,

    CONSTRAINT uq_daily_reward_claims_ledger_entry
        UNIQUE (ledger_entry_id),

    CONSTRAINT ck_daily_reward_claims_reward_amount_positive
        CHECK (reward_amount > 0)
);
-- Быстрый поиск последнего получения для проверки интервала 24 часа.
CREATE INDEX idx_daily_reward_claims_user_claimed_at
    ON daily_reward_claims (user_id, claimed_at DESC);

-- Полученный подарок нельзя переписать или удалить: это уже совершённое начисление.
-- В частности, изменение claimed_at могло бы изменить время доступности следующего подарка.
CREATE FUNCTION reject_daily_reward_claim_mutation()
    RETURNS trigger
    LANGUAGE plpgsql
AS 'BEGIN RAISE EXCEPTION ''daily_reward_claims is append-only''; END;';

-- Разрешаем добавлять новые получения, но запрещаем UPDATE и DELETE существующих строк.
-- Откату транзакции это не мешает: при ошибке всё начисление по-прежнему откатывается вместе.
CREATE TRIGGER daily_reward_claims_append_only
    BEFORE UPDATE OR DELETE ON daily_reward_claims
    FOR EACH ROW
    EXECUTE FUNCTION reject_daily_reward_claim_mutation();


-- Сохранённые результаты запросов на подарок: повтор запроса не должен начислять монеты ещё раз.
-- Если ответ потерялся в сети, клиент повторяет запрос с тем же Idempotency-Key,
-- а сервер возвращает сохранённый ответ. Для нового получения нужен новый ключ.
-- Сервер сначала ищет результат по ключу и только для нового ключа проверяет доступность подарка.
-- При начислении эта запись сохраняется вместе с кошельком, claim и ledger в одной транзакции.
CREATE TABLE daily_reward_requests (
    -- Уникальный ID сохранённого результата запроса.
    id UUID NOT NULL,

    -- Пользователь, отправивший запрос. Чужой ключ не даёт доступа к его результату.
    user_id UUID NOT NULL,

    -- UUID из заголовка Idempotency-Key. Повтор с тем же ключом возвращает сохранённый ответ.
    idempotency_key UUID NOT NULL,

    -- Итог первой обработки, который не меняется при повторе:
    -- GRANTED — подарок начислен; NOT_YET_AVAILABLE — с прошлого получения не прошло 24 часа.
    outcome VARCHAR(32) NOT NULL,

    -- При GRANTED — новое получение подарка; при NOT_YET_AVAILABLE — предыдущее,
    -- из-за которого следующий подарок пока недоступен. Оно должно принадлежать user_id:
    -- этот FK проверяет существование claim, а совпадение владельца проверяет сервер.
    claim_id UUID NOT NULL,

    -- Точный JSON-ответ первой обработки, без токенов и секретов.
    -- Баланс в нём исторический; актуальный баланс клиент запрашивает отдельно.
    response_snapshot JSONB NOT NULL,

    -- Когда результат запроса был сохранён.
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_daily_reward_requests
        PRIMARY KEY (id),

    CONSTRAINT fk_daily_reward_requests_wallet
        FOREIGN KEY (user_id)
        REFERENCES coin_wallets (user_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_daily_reward_requests_claim
        FOREIGN KEY (claim_id)
        REFERENCES daily_reward_claims (id)
        ON DELETE RESTRICT,

    CONSTRAINT uq_daily_reward_requests_user_key
        UNIQUE (user_id, idempotency_key),

    CONSTRAINT ck_daily_reward_requests_outcome
        CHECK (outcome IN ('GRANTED', 'NOT_YET_AVAILABLE'))
);
-- Поиск сохранённых запросов пользователя по времени.
CREATE INDEX idx_daily_reward_requests_user_created
    ON daily_reward_requests (user_id, created_at);

-- Сохранённый результат нельзя переписать: повтор должен возвращать первоначальный ответ.
-- Нельзя и просто удалить его: иначе старый ключ перестанет распознаваться как повтор.
-- Сюда сразу вставляется окончательный результат, а не промежуточная запись «в обработке».
CREATE FUNCTION reject_daily_reward_request_mutation()
    RETURNS trigger
    LANGUAGE plpgsql
AS 'BEGIN RAISE EXCEPTION ''daily_reward_requests is append-only''; END;';

-- Автоматически запрещает UPDATE и DELETE строк. Добавлять новые результаты можно.
CREATE TRIGGER daily_reward_requests_append_only
    BEFORE UPDATE OR DELETE ON daily_reward_requests
    FOR EACH ROW
    EXECUTE FUNCTION reject_daily_reward_request_mutation();

COMMENT ON TABLE coin_ledger_entries IS 'Неизменяемый журнал операций Coins.';
COMMENT ON TABLE daily_reward_claims IS 'История получения подарков; интервал 24 часа проверяет сервер в транзакции начисления.';
COMMENT ON TABLE daily_reward_requests IS 'Идемпотентные запросы ежедневной награды с сохранённым ответом.';
