"use client";

import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import Link from "next/link";
import { consent as consentCopy } from "@/content/legal";
import { site } from "@/content/site";
import { ui as strings, type UiStrings } from "@/content/ui";
import {
  attribution,
  newIdempotencyKey,
  submitLead,
  type LeadForm as FormType,
} from "@/lib/submit";
import { reachGoal } from "@/lib/analytics";
import { readProduct, readTopic } from "@/lib/lead-link";
import styles from "./LeadForm.module.css";

/**
 * Поля, у которых на этой форме есть свой ярлык ошибки под инпутом,
 * в порядке разметки.
 *
 * Один список на две задачи: по нему ищется первое поле с ошибкой для
 * переноса фокуса и по нему же отбираются ошибки, пришедшие с бэкенда.
 * Раньше это были два независимых литерала, и добавление поля требовало
 * вспомнить про оба. Забыть легко, а последствие тихое: промах фокуса
 * замечает только тот, кто ходит по форме с клавиатуры или со
 * скринридером, — то есть об ошибке никто не сообщит.
 *
 * Порядок здесь обязан совпадать с порядком полей в разметке: человек
 * должен попасть на первую ошибку сверху, а не на случайную.
 *
 */
const FIELDS = ["name", "company", "phone", "email", "product", "serialNumber", "message", "consent"] as const;

/**
 * Поля, которые бэкенд называет иначе, чем форма.
 *
 * Селектор изделия в разметке зовётся `product`, а в теле запроса —
 * `productSlug`: форма отправляет slug позиции каталога, и на сервере поле
 * названо по содержимому. Пока имя совпадало у всех полей, разбор ошибок
 * работал прямым сравнением — и на первом же расхождении ошибка по изделию
 * пропала бы молча: сервер отказал, форма показала общее «проверьте поля»,
 * а какое именно поле не так — не показала.
 */
const BACKEND_FIELDS: Record<string, string> = { productSlug: "product" };

type Field = (typeof FIELDS)[number];

/**
 * Строка запроса как внешнее хранилище для `useSyncExternalStore`.
 *
 * Функции объявлены снаружи компонента намеренно: у них обязана быть
 * постоянная ссылка, иначе React считает хранилище другим на каждом рендере
 * и подписывается заново.
 */
const NO_UPDATES = () => () => {};
const readSearch = () => window.location.search;
/** На сервере адреса нет — и это не пустой адрес, а его отсутствие. */
const readNoSearch = () => "";

type Errors = Partial<Record<Field, string>>;

// Проверка полей до отправки — валидация на границе доверия нужна независимо
// от того, куда запрос уйдёт потом. Те же правила стоят в LeadSubmission
// на бэкенде: браузеру верить нельзя, а пользователю нужно показать ошибку
// сразу, не гоняя запрос.
//
// Тексты ошибок приходят вторым аргументом, а не берутся из словаря внутри:
// проверка знает про поля, а не про подписи. Умолчание стоит на месте — так
// функция остаётся вызываемой одним аргументом, в том числе из тестов.
/**
 * Что именно требуется от этой формы.
 *
 * Сервисное обращение — единственное, где изделие и его серийный номер
 * обязательны: инженер едет к конкретному аппарату, а «другое или не знаю»
 * не аппарат. В запросе цены, каталога или партнёрства изделия у человека
 * ещё нет, и требовать его там значит не пускать в форму того, кто как раз
 * и пришёл выбирать.
 *
 * `productAsked` — не то же самое, что `service`. Список изделий приходит
 * с бэкенда, и когда каталог не ответил, селектора на форме нет вовсе.
 * Требовать в этот момент выбор изделия значит запереть сервисное
 * обращение целиком: поля нет, ошибка есть, отправить нельзя. Падение
 * каталога не должно отбирать у человека возможность позвать сервис.
 */
export type Requirements = {
  /** Форма сервиса: спрашиваем изделие и серийный номер строго. */
  service?: boolean;
  /** Есть ли на форме, из чего выбрать изделие. */
  productAsked?: boolean;
};

export function validate(
  data: FormData,
  messages: UiStrings["form"]["errors"] = strings.form.errors,
  need: Requirements = {},
): Errors {
  const errors: Errors = {};
  const get = (k: string) => String(data.get(k) ?? "").trim();

  if (!get("name")) errors.name = messages.name;
  // Организация обязательна на всех трёх формах, где стоит этот компонент:
  // на карточке изделия, на /contacts/ и на /service/ (просьба заказчика
  // от 16 сентября). Раньше её требовало только сервисное обращение.
  //
  // `get` обрезает пробелы: строка из одних пробелов — то же незаполненное
  // поле, что и пустая. Так же считает бэкенд — `String.isBlank`
  // в ServiceLeadNamesTheDeviceValidator.
  if (!get("company")) errors.company = messages.company;
  if (get("phone").replace(/\D/g, "").length < 10) errors.phone = messages.phone;
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(get("email"))) errors.email = messages.email;
  if (need.service && need.productAsked && !get("product")) {
    errors.product = messages.product;
  }
  // Формат серийного номера не проверяется: вид номера VEDAL в согласованных
  // материалах не описан, а маска, придуманная здесь, отклоняла бы настоящие
  // номера. Проверяем только наличие и длину — то же самое стоит на бэкенде.
  if (need.service && !get("serialNumber")) {
    errors.serialNumber = messages.serialRequired;
  } else if (get("serialNumber").length > 100) {
    errors.serialNumber = messages.serialNumber;
  }
  if (get("message").length < 10) errors.message = messages.message;
  if (!data.get("consent")) errors.consent = messages.consent;

  return errors;
}

export type Topic = { code: FormType; label: string };

/** Позиция каталога: то, чем связывается заявка с изделием. */
export type ProductRef = { slug: string; name: string; kind: string };

type Props = {
  /** Тип заявки. Если передан список тем, его перекрывает выбор пользователя. */
  form: FormType;
  /** Темы обращения. Если не переданы, селектор темы не показывается. */
  topics?: readonly Topic[];
  /**
   * Изделие, по которому оставляют заявку. Задано страницей — карточкой
   * изделия, — поэтому селектор не показывается вовсе: человек уже выбрал
   * изделие тем, что дошёл до его страницы, и второй выбор того же самого
   * это выбор, который можно сделать неправильно.
   *
   * Перекрывает {@link Props.products}: там, где изделие известно, список
   * каталога не нужен.
   */
  product?: ProductRef;
  /** Позиции каталога для селектора изделия. Приходят с бэкенда через страницу. */
  products?: readonly ProductRef[];
  analytics: string;
  submitLabel?: string;
  hint?: string;
  messageLabel?: string;
};

export default function LeadForm({
  form,
  topics,
  product,
  products = [],
  analytics,
  submitLabel,
  hint,
  messageLabel,
}: Props) {
  const [errors, setErrors] = useState<Errors>({});
  const [status, setStatus] = useState<"idle" | "sending" | "sent" | "failed">("idle");
  const [notice, setNotice] = useState("");

  // Что человек выбрал руками. `null` — ещё не выбирал.
  //
  // Выбор держится отдельно от действующей темы, а не поверх неё: тема
  // приходит ещё и из адреса, и в одном состоянии они спорили бы за то,
  // чья запись случится последней. Здесь спор решён правилом ниже,
  // и правило видно глазами.
  const [chosen, setChosen] = useState<FormType | null>(null);
  const [chosenProduct, setChosenProduct] = useState<string | null>(null);

  // Что попросили в адресе: `/contacts/?topic=quote&product=vedal-r1`.
  // Кнопки «Запросить КП» и «Запросить документ» ведут сюда именно так;
  // сборка такой ссылки и её разбор живут в одном месте — lib/lead-link.ts.
  //
  // `useSearchParams` брать нельзя: он переводит всю страницу на отрисовку
  // по запросу, а страницы сайта уезжают в статику на сборке — сайт обязан
  // открываться при упавшем бэкенде. Та же причина у components/Analytics.tsx.
  //
  // Отсюда и `useSyncExternalStore`, а не эффект с setState: адрес — это
  // внешнее по отношению к React значение, и читать его надо тем способом,
  // который знает про две картинки мира. На сервере строки запроса нет
  // (третий аргумент), поэтому первый клиентский рендер совпадает с
  // серверной разметкой и гидратация не расходится, а настоящий адрес
  // приезжает следом. Подписки нет: адрес страницы за её жизнь не меняется —
  // переход по ссылке размонтирует форму и смонтирует новую.
  const search = useSyncExternalStore(NO_UPDATES, readSearch, readNoSearch);
  const asked = {
    topic: readTopic(search),
    product: readProduct(search),
  };

  // Действующая тема. От неё зависит, показывать ли серийный номер, поэтому
  // она держится в состоянии, а не только в значении селектора: неуправляемый
  // select о смене выбора не сообщает, и поле не появлялось бы вовсе.
  //
  // Порядок один: выбор человека → тема из адреса → умолчание страницы.
  // Адрес задаёт начальное значение, а не запрет: человек по-прежнему
  // меняет тему руками, и его выбор старше ссылки, по которой он пришёл.
  //
  // Тема из адреса берётся только там, где тему вообще выбирают. На форме
  // сервиса и на карточке изделия её задаёт страница, и `?topic=partner`,
  // дописанный в такой адрес, не имеет права переспорить заголовок,
  // который человек читает над формой.
  const askedTopic =
    topics && asked.topic && topics.some((t) => t.code === asked.topic) ? asked.topic : null;
  const topic: FormType = chosen ?? askedTopic ?? (topics ? topics[0].code : form);

  // Изделие из адреса — только то, что есть в каталоге. Слаг приходит
  // снаружи, и подставленный не глядя он дал бы селектор с пустым значением
  // при заполненном на вид адресе: заявка уехала бы без изделия, а человек
  // видел бы, что изделие выбрано.
  const askedProduct =
    asked.product && products.some((p) => p.slug === asked.product) ? asked.product : "";
  const productValue = chosenProduct ?? askedProduct;

  // Серийный номер спрашивается только в сервисном обращении: в запросе цены,
  // каталога или партнёрства изделия у человека ещё нет, и поле там — шум.
  const asksSerial = topic === "service";

  // Ключ живёт столько же, сколько заполняемая форма: повторный клик по
  // «Отправить» не создаст вторую заявку. После успешной отправки берём новый —
  // следующее обращение с той же страницы должно быть отдельной заявкой.
  const idempotencyKey = useRef(newIdempotencyKey());

  // Атрибуция снимается один раз, при монтировании формы, а не в момент
  // отправки. Посетитель приходит по ссылке с меткой кампании, ходит по сайту
  // и отправляет заявку уже с другого адреса — прочитать метку при отправке
  // значит потерять её у всех, кто не заполнил форму на первой же странице.
  const attributed = useRef<{ language?: string; campaign?: string }>({});
  useEffect(() => {
    attributed.current = attribution(window.location.search, document.documentElement.lang);
  }, []);

  async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formEl = event.currentTarget;
    const data = new FormData(formEl);

    const found = validate(data, strings.form.errors, {
      service: asksSerial,
      productAsked: !!product || products.length > 0,
    });
    setErrors(found);
    if (Object.keys(found).length > 0) {
      // Фокус на первое поле с ошибкой. Без этого для незрячего посетителя
      // нажатие «Отправить» выглядит как «ничего не произошло»: сообщения
      // появляются рядом с полями, но ни фокус, ни живая область о них
      // не сообщают, и найти их можно, только заново обойдя всю форму.
      //
      // Порядок обхода — порядок полей в форме, а не порядок ключей объекта:
      // человек должен попасть на первую ошибку сверху, а не на случайную.
      const first = FIELDS.find((field) => found[field]);
      const control = first ? formEl.elements.namedItem(first) : null;
      if (control instanceof HTMLElement) control.focus();
      return;
    }

    setStatus("sending");
    setNotice("");

    const get = (k: string) => String(data.get(k) ?? "").trim();
    const result = await submitLead(
      {
        form: topic,
        name: get("name"),
        company: get("company") || undefined,
        phone: get("phone"),
        email: get("email"),
        productSlug: get("product") || undefined,
        // Поле снято с формы вместе со сменой темы: в FormData его нет,
        // и на бэкенд уедет undefined, а не номер от прошлого выбора.
        serialNumber: get("serialNumber") || undefined,
        message: get("message"),
        consent: data.get("consent") !== null,
        language: attributed.current.language,
        campaign: attributed.current.campaign,
        trap: get("trap") || undefined,
      },
      idempotencyKey.current,
    );

    if (result.ok) {
      setStatus("sent");
      setNotice(result.message);
      idempotencyKey.current = newIdempotencyKey();
      // Цель — на принятой заявке, а не на нажатии кнопки. Клик по «Отправить»
      // с пустым телефоном или при упавшем бэкенде отправкой формы не является,
      // и посчитанный как отправка он завышал бы конверсию ровно на те случаи,
      // ради которых её и смотрят.
      //
      // Есть селектор темы — цель идёт за темой. Форма на /contacts/ отправляет
      // и запрос цены, и запрос каталога, и сервисное обращение; посчитанные
      // одной целью, они дают одно число, из которого не видно, чего просили.
      // Имена совпадают со списком из чек-листа приёмки: `quote_form_submit`,
      // `catalog_form_submit`, `service_form_submit`.
      reachGoal(topics ? `${topic}_form_submit` : analytics);
      return;
    }

    // Бэкенд разбирает ошибку по полям — показываем их рядом с полями,
    // а не одной строкой над формой.
    if (result.fields) {
      const mapped: Errors = {};
      for (const [field, message] of Object.entries(result.fields)) {
        const own = BACKEND_FIELDS[field] ?? field;
        if ((FIELDS as readonly string[]).includes(own)) mapped[own as Field] = message;
      }
      setErrors(mapped);
    }
    setStatus("failed");
    setNotice(result.message);
  }

  const sending = status === "sending";

  // Успешная отправка убирает форму: повторно слать то же обращение незачем.
  if (status === "sent") {
    return (
      <div className={styles.form}>
        <p className={styles.pending} role="status" data-anim="rise">
          {notice}
        </p>
        <div className={styles.actions}>
          <button
            type="button"
            className={styles.submit}
            onClick={() => {
              setStatus("idle");
              setNotice("");
            }}
          >
            {strings.form.again}
          </button>
        </div>
      </div>
    );
  }

  return (
    <form className={`${styles.form} ym-hide-content`} onSubmit={onSubmit} noValidate>
      {topics && (
        <div className={`${styles.field} ${styles.fieldWide} ${styles.fieldFirst}`}>
          <label className={styles.label} htmlFor="topic">
            {strings.form.topic}
          </label>
          <select
            id="topic"
            name="topic"
            className={styles.select}
            value={topic}
            onChange={(event) => setChosen(event.target.value as FormType)}
          >
            {/* Названия тем приходят из content/contacts.ts — это содержание,
                а не подпись поля: тема определяет, куда уедет заявка. */}
            {topics.map((t) => (
              <option key={t.code} value={t.code}>
                {t.label}
              </option>
            ))}
          </select>
        </div>
      )}

      <div className={`${styles.row} ${topics ? styles.rowSpaced : ""}`}>
        <div className={styles.field}>
          <label className={styles.label} htmlFor="name">
            {strings.form.name} <span className={styles.required}>*</span>
          </label>
          <input
            id="name"
            name="name"
            className={`${styles.input} ${errors.name ? styles.invalid : ""}`}
            autoComplete="name"
            aria-invalid={!!errors.name}
            aria-required="true"
            aria-describedby={errors.name ? "name-error" : undefined}
          />
          {errors.name && <span id="name-error" className={styles.error}>{errors.name}</span>}
        </div>

        <div className={styles.field}>
          <label className={styles.label} htmlFor="company">
            {strings.form.company} <span className={styles.required}>*</span>
          </label>
          <input
            id="company"
            name="company"
            className={`${styles.input} ${errors.company ? styles.invalid : ""}`}
            autoComplete="organization"
            aria-invalid={!!errors.company}
            aria-required="true"
            aria-describedby={errors.company ? "company-error" : undefined}
          />
          {errors.company && (
            <span id="company-error" className={styles.error}>{errors.company}</span>
          )}
        </div>

        <div className={styles.field}>
          <label className={styles.label} htmlFor="phone">
            {strings.form.phone} <span className={styles.required}>*</span>
          </label>
          <input
            id="phone"
            name="phone"
            type="tel"
            className={`${styles.input} ${errors.phone ? styles.invalid : ""}`}
            autoComplete="tel"
            aria-invalid={!!errors.phone}
            aria-required="true"
            aria-describedby={errors.phone ? "phone-error" : undefined}
          />
          {errors.phone && <span id="phone-error" className={styles.error}>{errors.phone}</span>}
        </div>

        <div className={styles.field}>
          <label className={styles.label} htmlFor="email">
            {strings.form.email} <span className={styles.required}>*</span>
          </label>
          <input
            id="email"
            name="email"
            type="email"
            className={`${styles.input} ${errors.email ? styles.invalid : ""}`}
            autoComplete="email"
            aria-invalid={!!errors.email}
            aria-required="true"
            aria-describedby={errors.email ? "email-error" : undefined}
          />
          {errors.email && <span id="email-error" className={styles.error}>{errors.email}</span>}
        </div>
      </div>

      {/* Изделие известно странице — показываем его, а не спрашиваем.
          Скрытое поле названо так же, как селектор ниже: отправка читает
          `product` из FormData и про разницу между «выбрали» и «пришли
          с карточки» знать не обязана.

          Название изделия и его направление приходят из каталога, а не
          из словаря интерфейса. */}
      {product ? (
        <div className={`${styles.field} ${styles.fieldWide}`}>
          <p className={styles.label}>{strings.form.product}</p>
          <p className={styles.fixed}>
            {product.name} — {product.kind}
          </p>
          <input type="hidden" name="product" value={product.slug} />
        </div>
      ) : products.length === 0 ? null : (
        <div className={`${styles.field} ${styles.fieldWide}`}>
          <label className={styles.label} htmlFor="product">
            {strings.form.product}
            {asksSerial && <> <span className={styles.required}>*</span></>}
          </label>
          {/* Значение — slug, а не название: бэкенд связывает заявку с позицией
              каталога по нему. Название в базе может смениться, slug — нет. */}
          <select
            id="product"
            name="product"
            className={`${styles.select} ${errors.product ? styles.invalid : ""}`}
            /* Управляемый, потому что изделие тоже приходит из адреса:
               defaultValue учитывает только первый рендер, а адрес прочитан
               эффектом уже после него. */
            value={productValue}
            onChange={(event) => setChosenProduct(event.target.value)}
            aria-invalid={!!errors.product}
            aria-required={asksSerial ? "true" : undefined}
            aria-describedby={errors.product ? "product-error" : undefined}
          >
            {/* Первая строка списка меняется вместе с темой. «Другое или не знаю»
                для сервисного обращения — не ответ: инженеру ехать к аппарату,
                а по «не знаю» нечего искать. Там же ниже спрашивается серийный
                номер, и одно с другим не сходится: номер знают у того изделия,
                которое как раз и не выбрали. */}
            <option value="">
              {asksSerial ? strings.form.productChoose : strings.form.productOther}
            </option>
            {products.map((p) => (
              <option key={p.slug} value={p.slug}>
                {p.name} — {p.kind}
              </option>
            ))}
          </select>
          {errors.product && (
            <span id="product-error" className={styles.error}>
              {errors.product}
            </span>
          )}
        </div>
      )}

      {asksSerial && (
        <div className={`${styles.field} ${styles.fieldWide}`}>
          <label className={styles.label} htmlFor="serialNumber">
            {strings.form.serialNumber} <span className={styles.required}>*</span>
          </label>
          <input
            id="serialNumber"
            name="serialNumber"
            className={`${styles.input} ${errors.serialNumber ? styles.invalid : ""}`}
            /* Автозаполнение выключено: номера аппарата в профиле браузера нет,
               а подставленный им телефон или адрес уедет сервисному инженеру
               как серийный номер — это хуже пустого поля. */
            autoComplete="off"
            aria-invalid={!!errors.serialNumber}
            aria-required="true"
            aria-describedby={errors.serialNumber ? "serial-error" : "serial-hint"}
          />
          {errors.serialNumber ? (
            <span id="serial-error" className={styles.error}>
              {errors.serialNumber}
            </span>
          ) : (
            <span id="serial-hint" className={styles.fieldHint}>
              {strings.form.serialHint}
            </span>
          )}
        </div>
      )}

      <div className={`${styles.field} ${styles.fieldWide}`}>
        <label className={styles.label} htmlFor="message">
          {messageLabel ?? strings.form.message} <span className={styles.required}>*</span>
        </label>
        <textarea
          id="message"
          name="message"
          className={`${styles.textarea} ${errors.message ? styles.invalid : ""}`}
          aria-invalid={!!errors.message}
          aria-required="true"
          aria-describedby={errors.message ? "message-error" : undefined}
        />
        {errors.message && <span id="message-error" className={styles.error}>{errors.message}</span>}
      </div>

      {/* Ловушка для ботов. Скрыта от человека и от скринридера, автозаполнение
          выключено: браузер не должен подставить сюда значение сам. */}
      <input
        type="text"
        name="trap"
        tabIndex={-1}
        autoComplete="off"
        aria-hidden="true"
        className={styles.trap}
      />

      {/* §14.6: рядом с согласием стоит ссылка на политику — иначе человек
          подписывается под документом, которого не видел. */}
      <label className={styles.consent}>
        <input
          type="checkbox"
          name="consent"
          aria-invalid={!!errors.consent}
          aria-required="true"
          aria-describedby={errors.consent ? "consent-error" : undefined}
        />
        {/* Звёздочка вплотную к тексту, разделитель с воздухом. Раньше между
            ними стояли два пробела подряд, и строка читалась как «данных * ·
            Политика» — набор знаков, а не подпись со ссылкой. */}
        {/* Формулировку согласия экран не сочиняет: бэкенд хранит её версию,
            под которой человек подписался, и текст обязан совпасть с той,
            что лежит в content/legal.ts. */}
        <span>
          {consentCopy.label}
          <span className={styles.required}>*</span>
          <span className={styles.consentSep}>·</span>
          <Link href={consentCopy.href}>{consentCopy.linkLabel}</Link>
        </span>
      </label>
      {errors.consent && <span id="consent-error" className={styles.error}>{errors.consent}</span>}
      <p className={styles.consentNote}>
        {consentCopy.note}
      </p>

      <div className={styles.actions}>
        <button
          type="submit"
          className={styles.submit}
          disabled={sending}
        >
          {sending ? strings.form.sending : (submitLabel ?? strings.form.submit)}
        </button>
        {hint && <span className={styles.hint}>{hint}</span>}
      </div>

      {status === "failed" && (
        <p className={styles.pending} role="alert" data-anim="rise">
          {notice} {strings.form.fallbackCall}{" "}
          <a href={`tel:${site.phone.replace(/\s/g, "")}`}>{site.phone}</a>{" "}
          {strings.form.fallbackWrite} <a href={`mailto:${site.email}`}>{site.email}</a>
          {strings.form.fallbackEnd}
        </p>
      )}
    </form>
  );
}
