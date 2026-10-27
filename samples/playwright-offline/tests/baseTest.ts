import { test as BaseTest } from "@playwright/test";
import LoginPage from "../pages/loginPage.page";
import Header from "../pages/blocks/header.block";

const test = BaseTest.extend<{
    loginPage: LoginPage;
    header: Header;
}>({
    loginPage: async ({ page }, use) => { await use(new LoginPage(page)) },
    header: async ({ page }, use) => { await use(new Header(page)) }
})
export default test;
export const expect = test.expect;
