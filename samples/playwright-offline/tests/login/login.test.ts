import test, { expect } from "../baseTest";
import users from "../../data/users.json" with { type: "json" };

test.describe("Login", () => {
    test("admin sees greeting", async ({ page, loginPage }) => {
        const user = users.admin;

        await test.step("Open login form", async () => {
            await loginPage.open();
        });

        await test.step("Sign in", async () => {
            await loginPage.login(user.login, user.password);
        });

        const greeting = await page.textContent(loginPage.selectors.greeting);
        expect(greeting).toBe("Hello, " + user.login);
    });

    test("header shows title", async ({ page, header }) => {
        const title = "Playwright offline sample";
        await header.render(title);
        await expect(page.locator(header.selectors.title)).toHaveText(title);
    });
});
