import {describe,it,expect} from "vitest";
import {assignItemIds,itemAt,itemLocations,changeItems} from "./collection-tree";
describe("collection editing",()=>{
 const sample=()=>assignItemIds([{name:"Orders",item:[{name:"Get",request:{method:"GET"}},{name:"Post",request:{method:"POST"}}]},{name:"Archive",item:[]}]);
 it("deletes a request and reindexes surviving requests by stable identity",()=>{const items=sample(),id=itemAt(items,[0,1])!["x-studio-id"];const changed=changeItems(items,"delete",[0,0]);expect(itemLocations(changed).find(x=>x.item["x-studio-id"]===id)?.path).toEqual([0,0]);expect(items[0].item).toHaveLength(2);});
 it("duplicates folders with independent nested request identities",()=>{const items=sample(),changed=changeItems(items,"duplicate",[0]);expect(changed[1].name).toBe("Orders copy");expect(changed[1].item[0]["x-studio-id"]).not.toBe(items[0].item[0]["x-studio-id"]);});
 it("moves requests between folders without losing their ID or data",()=>{const items=sample(),id=items[0].item[0]["x-studio-id"];const changed=changeItems(items,"move",[0,0],"",[1]);expect(changed[1].item[0].request.method).toBe("GET");expect(changed[1].item[0]["x-studio-id"]).toBe(id);expect(changed[0].item).toHaveLength(1);});
 it("rejects moving folders into themselves and stale paths",()=>{const items=sample();expect(()=>changeItems(items,"move",[0],"",[0])).toThrow("inside itself");expect(()=>changeItems(items,"delete",[6])).toThrow("Reload");expect(()=>changeItems(items,"rename",[0]," ")).toThrow("name");});
});
