import type {Row} from "./api";
export function assignItemIds(items:Row[],fresh=false):Row[]{return items.map(item=>({...item,"x-studio-id":!fresh&&item["x-studio-id"]||crypto.randomUUID(),...(Array.isArray(item.item)?{item:assignItemIds(item.item,fresh)}:{})}));}
export function itemAt(items:Row[],path:number[]):Row|undefined{let node:Row|undefined;for(const index of path){node=items[index];if(!node)return undefined;items=node.item||[];}return node;}
export function itemLocations(items:Row[],path:number[]=[]):{item:Row;path:number[]}[]{return items.flatMap((item,i)=>[{item,path:[...path,i]},...(Array.isArray(item.item)?itemLocations(item.item,[...path,i]):[])]);}
export function changeItems(items:Row[],action:"rename"|"duplicate"|"delete"|"move",path:number[],name="",destination:number[]=[]):Row[]{
 const result=structuredClone(items),node=itemAt(result,path);if(!node||!path.length)throw new Error("This item changed. Reload the collection.");const parent=path.length>1?itemAt(result,path.slice(0,-1))?.item:result;if(!Array.isArray(parent))throw new Error("Parent folder is missing.");
 if(action==="rename"){if(!name.trim())throw new Error("Enter an item name.");node.name=name.trim();}
 if(action==="delete")parent.splice(path[path.length-1],1);
 if(action==="duplicate"){const copy=assignItemIds([node],true)[0];copy.name=(node.name||"Request")+" copy";parent.splice(path[path.length-1]+1,0,copy);}
 if(action==="move"){if(destination.length>=path.length&&path.every((value,i)=>destination[i]===value))throw new Error("A folder cannot be moved inside itself.");const target=destination.length?itemAt(result,destination):undefined;if(destination.length&&!Array.isArray(target?.item))throw new Error("Choose a folder destination.");parent.splice(path[path.length-1],1);(target?target.item:result).push(node);}
 return result;
}
