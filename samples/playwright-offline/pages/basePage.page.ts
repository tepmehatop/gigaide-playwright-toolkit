import { Page } from "@playwright/test";

export interface Opens {
    open(): Promise<void>;
}

export default abstract class BasePage implements Opens {
    constructor(protected page: Page) {
    }

    abstract open(): Promise<void>;

    public async title(): Promise<string> {
        return this.page.title();
    }
}
