import { Page } from "@playwright/test";

export default class LoginPage {
    private page: Page;

    constructor(page: Page) {
        this.page = page;
    }

    selectors = {
        loginInput: "#login",
        passwordInput: "#password",
        submitButton: "#submit",
        greeting: "#greeting"
    }

    public async open() {
        await this.page.setContent(`
            <form onsubmit="event.preventDefault(); document.querySelector('#greeting').textContent = 'Hello, ' + document.querySelector('#login').value">
                <input id="login"><input id="password" type="password"><button id="submit">Sign in</button>
            </form>
            <div id="greeting"></div>`);
    }

    public async login(login: string, password: string) {
        await this.page.fill(this.selectors.loginInput, login);
        await this.page.fill(this.selectors.passwordInput, password);
        await this.page.click(this.selectors.submitButton);
    }
}
