import { Page } from "@playwright/test";

export default class Header {
    protected page: Page;

    constructor(page: Page) {
        this.page = page;
    }

    selectors = {
        title: "h1"
    }

    public async render(title: string) {
        await this.page.setContent(`<h1>${title}</h1>`);
    }
}
