import BasePage from "./basePage.page";

export default class SearchPage extends BasePage {
    public async open() {
        await this.page.setContent(`<input id="q">`);
    }

    public async search(text: string) {
        await this.page.fill("#q", text);
        return this.title();
    }
}
